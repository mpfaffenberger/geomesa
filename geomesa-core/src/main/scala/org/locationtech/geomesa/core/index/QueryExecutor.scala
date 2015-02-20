/*
 * Copyright 2015 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.core.index

import java.util.Map.Entry

import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory}
import org.apache.accumulo.core.data.{Value, Key}
import org.geotools.data.{DataUtilities, Query}
import org.geotools.factory.CommonFactoryFinder
import org.geotools.geometry.jts.ReferencedEnvelope
import org.locationtech.geomesa.core.data._
import org.locationtech.geomesa.core.filter._
import org.locationtech.geomesa.core.index.QueryHints._
import org.locationtech.geomesa.core.index.strategies._
import org.locationtech.geomesa.core.iterators.{DensityIterator, TemporalDensityIterator, DeDuplicatingIterator}
import org.locationtech.geomesa.core.iterators.TemporalDensityIterator._
import org.locationtech.geomesa.core.security.SecurityUtils
import org.locationtech.geomesa.core.util.{SelfClosingIterator, CloseableIterator}
import org.locationtech.geomesa.feature.FeatureEncoding.FeatureEncoding
import org.locationtech.geomesa.feature.{SimpleFeatureEncoder, ScalaSimpleFeatureFactory, SimpleFeatureDecoder}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.{SimpleFeatureType, SimpleFeature}
import org.opengis.filter.sort.{SortOrder, SortBy}
import org.locationtech.geomesa.core.util.CloseableIterator._
import scala.reflect.ClassTag

/**
 * Executes a query against geomesa
 */
class QueryExecutor(sft: SimpleFeatureType,
                    featureEncoding: FeatureEncoding,
                    stSchema: String,
                    timeSchema: String,
                    acc: AccumuloConnectorCreator,
                    hints: StrategyHints) extends ExplainingLogging with IndexFilterHelpers {

  val hasDupes = IndexSchema.mayContainDuplicates(sft)

  val featureEncoder = SimpleFeatureEncoder(sft, featureEncoding)
  val featureDecoder = SimpleFeatureDecoder(sft, featureEncoding)

  type KVIter = CloseableIterator[Entry[Key,Value]]
  type SFIter = CloseableIterator[SimpleFeature]

  /**
   * Execute a query against geomesa
   *
   * @param query
   * @return
   */
  def query(query: Query): SFIter = {
    // Perform the query
    val accumuloIterator = getIterator(query, log)
    // Convert Accumulo results to SimpleFeatures
    adaptIterator(accumuloIterator, query)
  }

  /**
   * Plan the query, but don't execute it - used for explain query
   */
  def planQuery(query: Query, output: ExplainerOutputType = log): Unit = {
    getIterator(query, output)
  }

  /**
   * Gets the accumulo iterator returning raw key/value pairs
   */
  private def getIterator(query: Query, output: ExplainerOutputType): KVIter = {

    output(s"Running ${ExplainerOutputType.toString(query)}")
    val isDensity = query.getHints.containsKey(BBOX_KEY)

    def flatten(queries: Seq[Query]): KVIter =
      queries.toIterator.ciFlatMap(getIterator( _, isDensity, output))

    // in some cases, where duplicates may appear in overlapping queries or the data itself, remove them
    def deduplicate(queries: Seq[Query]): KVIter = {
      val flatQueries = flatten(queries)
      val dedupe = (key: Key, value: Value) => featureDecoder.extractFeatureId(value.get)
      new DeDuplicatingIterator(flatQueries, dedupe)
    }

    if (isDensity) {
      val env = query.getHints.get(BBOX_KEY).asInstanceOf[ReferencedEnvelope]
      val q1 = new Query(sft.getTypeName, ff.bbox(ff.property(sft.getGeometryDescriptor.getLocalName), env))
      val mixedQuery = DataUtilities.mixQueries(q1, query, "geomesa.mixed.query")
      if (hasDupes) {
        deduplicate(Seq(mixedQuery))
      } else {
        flatten(Seq(mixedQuery))
      }
    } else {
      // As a pre-processing step, we examine the query/filter and split it into multiple queries.
      // TODO Work to make the queries non-overlapping
      val rawQueries = splitQueryOnOrs(query, output)
      if (hasDupes || rawQueries.length > 1) {
        deduplicate(rawQueries)
      } else {
        flatten(rawQueries)
      }
    }
  }

  private def getIterator(query: Query, isDensity: Boolean, output: ExplainerOutputType): KVIter = {
    val strategy = QueryStrategyDecider.chooseStrategy(sft, query, hints, acc.geomesaVersion(sft))

    output(s"Strategy: ${strategy.getClass.getCanonicalName}")
    output(s"Transforms: ${query.getHints.get(TRANSFORMS)}")

    val indexSchema = strategy match {
      case s: STIdxStrategy     => stSchema
      case s: TimeIndexStrategy => timeSchema
      case _: RecordIdxStrategy | _: AttributeIdxStrategy => null // not used
    }

    strategy.execute(query, sft, indexSchema, featureEncoding, acc, output)
  }

  private def splitQueryOnOrs(query: Query, output: ExplainerOutputType): Seq[Query] = {
    val originalFilter = query.getFilter
    output(s"Original filter: $originalFilter")

    val rewrittenFilter = rewriteFilterInDNF(originalFilter)
    output(s"Rewritten filter: $rewrittenFilter")

    val orSplitter = new OrSplittingFilter
    val splitFilters = orSplitter.visit(rewrittenFilter, null)

    // Let's just check quickly to see if we can eliminate any duplicates.
    val filters = splitFilters.distinct

    filters.map { filter =>
      val q = new Query(query)
      q.setFilter(filter)
      q
    }
  }

  // This function decodes/transforms that Iterator of Accumulo Key-Values into an Iterator of SimpleFeatures.
  def adaptIterator(accumuloIterator: KVIter, query: Query): SFIter = {
    // Perform a projecting decode of the simple feature
    val returnSFT = getReturnSFT(query)
    val decoder = SimpleFeatureDecoder(returnSFT, featureEncoding)

    // Decode according to the SFT return type.
    // if this is a density query, expand the map
    if (query.getHints.containsKey(DENSITY_KEY)) {
      adaptDensityIterator(accumuloIterator, decoder)
    } else if (query.getHints.containsKey(TEMPORAL_DENSITY_KEY)) {
      adaptTemporalIterator(accumuloIterator, returnSFT, decoder)
    } else {
      adaptStandardIterator(accumuloIterator, query, decoder)
    }
  }

  private def adaptStandardIterator(accumuloIterator: KVIter,
                                    query: Query,
                                    decoder: SimpleFeatureDecoder): SFIter = {
    val features = accumuloIterator.map { kv =>
      val ret = decoder.decode(kv.getValue.get)
      val visibility = kv.getKey.getColumnVisibility
      if(visibility != null && !EMPTY_VIZ.equals(visibility)) {
        ret.getUserData.put(SecurityUtils.FEATURE_VISIBILITY, visibility.toString)
      }
      ret
    }

    if (query.getSortBy != null && query.getSortBy.length > 0) {
      sort(features, query.getSortBy)
    } else {
      features
    }
  }

  private def adaptTemporalIterator(accumuloIterator: KVIter,
                                    returnSFT: SimpleFeatureType,
                                    decoder: SimpleFeatureDecoder): SFIter = {
    val timeSeriesStrings = accumuloIterator.map { kv =>
      decoder.decode(kv.getValue.get).getAttribute(ENCODED_TIME_SERIES).toString
    }
    val summedTimeSeries = timeSeriesStrings.map(decodeTimeSeries).reduce(combineTimeSeries)

    val zeroPoint = new GeometryFactory().createPoint(new Coordinate(0,0))

    val featureBuilder = ScalaSimpleFeatureFactory.featureBuilder(returnSFT)
    featureBuilder.add(TemporalDensityIterator.encodeTimeSeries(summedTimeSeries))
    featureBuilder.add(zeroPoint) // Filler value as Feature requires a geometry
    val result = featureBuilder.buildFeature(null)

    Seq(result).iterator
  }

  def adaptDensityIterator(accumuloIterator: KVIter, decoder: SimpleFeatureDecoder): SFIter =
    accumuloIterator.flatMap(kv => DensityIterator.expandFeature(decoder.decode(kv.getValue.get)))

  private def sort(features: SFIter, sortBy: Array[SortBy]): SFIter = {
    val sortOrdering = sortBy.map {
      case SortBy.NATURAL_ORDER => Ordering.by[SimpleFeature, String](_.getID)
      case SortBy.REVERSE_ORDER => Ordering.by[SimpleFeature, String](_.getID).reverse
      case sb                   =>
        val prop = sb.getPropertyName.getPropertyName
        val ord  = attributeToComparable(prop)
        if (sb.getSortOrder == SortOrder.DESCENDING) ord.reverse else ord
    }
    val comp: (SimpleFeature, SimpleFeature) => Boolean =
      if (sortOrdering.length == 1) {
        // optimized case for one ordering
        val ret = sortOrdering.head
        (l, r) => ret.compare(l, r) < 0
      }  else {
        (l, r) => sortOrdering.map(_.compare(l, r)).find(_ != 0).getOrElse(0) < 0
      }
    CloseableIterator(features.toList.sortWith(comp).iterator)
  }

  def attributeToComparable[T <: Comparable[T]](prop: String)(implicit ct: ClassTag[T]): Ordering[SimpleFeature] =
    Ordering.by[SimpleFeature, T](_.getAttribute(prop).asInstanceOf[T])

  // This function calculates the SimpleFeatureType of the returned SFs.
  private def getReturnSFT(query: Query): SimpleFeatureType =
    if (query.getHints.containsKey(DENSITY_KEY)) {
      SimpleFeatureTypes.createType(sft.getTypeName, DensityIterator.DENSITY_FEATURE_STRING)
    } else if (query.getHints.containsKey(TEMPORAL_DENSITY_KEY)) {
      SimpleFeatureTypes.createType(sft.getTypeName, TemporalDensityIterator.TEMPORAL_DENSITY_FEATURE_STRING)
    } else if (query.getHints.get(TRANSFORM_SCHEMA) != null) {
      query.getHints.get(TRANSFORM_SCHEMA).asInstanceOf[SimpleFeatureType]
    } else {
      sft
    }
}