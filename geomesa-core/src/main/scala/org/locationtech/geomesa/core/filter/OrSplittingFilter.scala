/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
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

package org.locationtech.geomesa.core.filter

import org.geotools.filter.visitor.DefaultFilterVisitor
import org.opengis.filter._

import scala.collection.JavaConversions._

class OrSplittingFilter extends DefaultFilterVisitor {

  // This function really returns a Seq[Filter].
  override def visit(filter: Or, data: scala.Any): AnyRef = {
    filter.getChildren.flatMap { subfilter =>
      this.visit(subfilter, data)
    }
  }

  def visit(filter: Filter, data: scala.Any): Seq[Filter] = {
    filter match {
      case o: Or => visit(o, data).asInstanceOf[Seq[Filter]]
      case _     => Seq(filter)
    }
  }
}
