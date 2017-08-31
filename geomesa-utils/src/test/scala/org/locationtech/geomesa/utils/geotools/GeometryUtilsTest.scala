package org.locationtech.geomesa.utils.geotools

import com.vividsolutions.jts.geom.Coordinate
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class GeometryUtilsTest extends Specification {

  "Geometry Utils" should {

    "calculate intercept of anti-meridian" >> {
      val p1 = new Coordinate(179, 5, 0)
      val p2 = new Coordinate(-179, 7, 0)
      (GeometryUtils.calcIDLIntercept(p1, p2).toInt, GeometryUtils.calcIDLIntercept(p2,p1).toInt) mustEqual (6, 6)
    }

    "calculate intercept of arbitrary longitude" >> {
      val p1 = new Coordinate(170, 5, 0)
      val p2 = new Coordinate(172, 7, 0)
      (GeometryUtils.calcCrossLat(p1, p2, 171).toInt, GeometryUtils.calcCrossLat(p2, p1, 171).toInt) mustEqual (6, 6)
    }

  }
}
