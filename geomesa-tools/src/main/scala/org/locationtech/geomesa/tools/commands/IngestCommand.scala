/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.geomesa.tools.commands

import java.io.File

import com.beust.jcommander.{JCommander, Parameter}
import com.typesafe.scalalogging.slf4j.Logging
import org.locationtech.geomesa.tools.Utils.Formats._
import org.locationtech.geomesa.tools._
import org.locationtech.geomesa.tools.commands.IngestCommand._

class IngestCommand(parent: JCommander) extends Command with Logging {

  val params = new IngestParameters()
  parent.addCommand(Command, params)

  override def execute(): Unit =
    getFileExtension(params.file) match {
      case CSV | TSV => new DelimitedIngest(params).run()
      case SHP       => new ShpIngest(params).run()
      case _         =>
        logger.error("Error: File format not supported for file " + params.file.getPath + ". Supported formats" +
          "are csv,tsv,shp")
    }

}

object IngestCommand {
  val Command = "ingest"

  class IngestParameters extends CreateParams {
    @Parameter(names = Array("--indexSchema"), description = "GeoMesa index schema format string")
    var indexSchema: String = null

    @Parameter(names = Array("--cols", "--columns"), description = "the set of column indexes to be ingested, must match the SimpleFeatureType spec")
    var columns: String = null

    @Parameter(names = Array("--dtFormat"), description = "format string for the date time field")
    var dtFormat: String = null

    @Parameter(names = Array("--idFields"), description = "the set of attributes to combine together to create a unique id for the feature")
    var idFields: String = null

    @Parameter(names = Array("--hash" , "-h"), description = "flag to toggle using md5hash as the feature id")
    var hash: Boolean = false

    @Parameter(names = Array("--lat"), description = "name of the latitude field in the SimpleFeatureType if ingesting point data")
    var lat: String = null

    @Parameter(names = Array("--lon"), description = "name of the longitude field in the SimpleFeatureType if ingesting point data")
    var lon: String = null

    @Parameter(names = Array("--file"), description = "the file to be ingested", required = true)
    var file: File = null
  }
}