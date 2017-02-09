SparkSQL
--------

GeoMesa SparkSQL support builds upon the ``DataSet``/``DataFrame`` API present
in the Spark SQL module to provide geospatial capabilities. This includes
custom geospatial data types and functions, the ability to create a DataFrame
from a GeoTools DataStore, and optimizations to improve SQL query performance.

GeoMesa SparkSQL code is provided by the ``geomesa-spark-sql`` module:

.. code-block:: xml

    <dependency>
      <groupId>org.locationtech.geomesa</groupId>
      <artifactId>geomesa-spark-sql_2.11</artifactId>
      // version, etc.
    </dependency>

Example
^^^^^^^

The following is a Scala example of connecting to GeoMesa Accumulo
via SparkSQL:

.. code-block:: scala

    // DataStore params to a hypothetical GeoMesa Accumulo table
    val dsParams = Map(
      "instanceId" -> "instance",
      "zookeepers" -> "zoo1,zoo2,zoo3",
      "user"       -> "user",
      "password"   -> "*****",
      "auths"      -> "USER,ADMIN",
      "tableName"  -> "geomesa_catalog")

    // Create SparkSession
    val sparkSession = SparkSession.builder()
      .appName("testSpark")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.kryo.registrator", classOf[GeoMesaSparkKryoRegistrator].getName)
      .config("spark.sql.crossJoin.enabled", "true")
      .master("local[*]")
      .getOrCreate()

    // Create DataFrame using the "geomesa" format
    val dataFrame = sparkSession.read
      .format("geomesa")
      .options(dsParams)
      .option("geomesa.feature", "chicago")
      .load()
    dataFrame.createOrReplaceTempView("chicago")

    // Query against the "chicago" schema
    val sqlQuery = "select * from chicago where st_contains(st_makeBBOX(0.0, 0.0, 90.0, 90.0), geom)"
    val resultDataFrame = sparkSession.sql(sqlQuery)

    resultDataFrame.show
    /*
    +-------+------+-----------+--------------------+-----------------+
    |__fid__|arrest|case_number|                 dtg|             geom|
    +-------+------+-----------+--------------------+-----------------+
    |      4|  true|          4|2016-01-04 00:00:...|POINT (76.5 38.5)|
    |      5|  true|          5|2016-01-05 00:00:...|    POINT (77 38)|
    |      6|  true|          6|2016-01-06 00:00:...|    POINT (78 39)|
    |      7|  true|          7|2016-01-07 00:00:...|    POINT (20 20)|
    |      9|  true|          9|2016-01-09 00:00:...|    POINT (50 50)|
    +-------+------+-----------+--------------------+-----------------+
    */

Usage
^^^^^

Because GeoMesa SparkSQL stacks on top of the ``geomesa-spark-core`` module,
one or more of the ``SpatialRDDProvider`` implementations, as described in
:doc:`/user/spark/core`, must be included on the classpath.

As well, Spark should be configured to register ``GeoMesaSparkKryoRegistor``. This
can be done when creating the ``SparkSession``:

.. code-block:: scala

    val sparkSession = SparkSession.builder()
      .appName("testSpark")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.kryo.registrator", classOf[GeoMesaSparkKryoRegistrator].getName)
      .master("local[*]")
      .getOrCreate()

If you will be ``JOIN``-ing multiple ``DataFrame``\s together, it will be necessary
to add the ``spark.sql.crossJoin.enabled`` property as well.

.. code-block:: scala

    .config("spark.sql.crossJoin.enabled", "true")

.. warning::

    Cross-joins can be very, very inefficient. Take care to ensure that one or both
    sets of data joined are very small, and consider using the ``broadcast()`` method
    to ensure that at least one ``DataFrame`` joined is in memory.


To create a GeoMesa SparkSQL-enabled ``DataFrame`` with data corresponding to a particular
feature type, do the following:

.. code-block:: scala

    // dsParams contains the parameters to pass to the data store
    val dataFrame = sparkSession.read
      .format("geomesa")
      .options(dsParams)
      .option("geomesa.feature", typeName)
      .load()

Specifically, invoking ``format("geomesa")`` registers the GeoMesa SparkSQL data source, and
``option("geomesa.feature", typeName)`` tells GeoMesa to use the feature type
named  ``typeName``. This also registers the custom user-defined types and functions
implemented in GeoMesa SparkSQL.

By registering a ``DataFrame`` as a temporary view, it is possible to access
this data frame in subsequent SQL calls. For example:

.. code-block:: scala

    dataFrame.createOrReplaceTempView("chicago")

makes it possible to call this data frame via the alias "chicago":

.. code-block:: scala

    val sqlQuery = "select * from chicago where st_contains(st_makeBBOX(0.0, 0.0, 90.0, 90.0), geom)"
    val resultDataFrame = sparkSession.sql(sqlQuery)

Registering user-defined types and functions can also be done manually by invoking
``SQLTypes.init()`` on the ``SQLContext`` object of the Spark session:

.. code-block:: scala

    SQLTypes.init(sparkSession.sqlContext)

Geospatial User-defined Types and Functions
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The GeoMesa SparkSQL module takes several `classes representing geometry objects`_
(as described by the OGC `OpenGIS Simple feature access common architecture`_ specification and
implemented by the Java Topology Suite) and registers them as user-defined types (UDTs) in
SparkSQL. These types are:

 * ``Geometry``
 * ``Point``
 * ``LineString``
 * ``Polygon``
 * ``MultiPoint``
 * ``MultiLineString``
 * ``MultiPolygon``
 * ``GeometryCollection``

GeoMesa SparkSQL also implements a subset of the functions described in the
OGC `OpenGIS Simple feature access SQL option`_ specification as SparkSQL
user-defined functions (UDFs). These include functions
for creating geometries, accessing properties of geometries, casting
Geometry objects to more specific subclasses, outputting geometries in other
formats, measuring spatial relationships between geometries, and processing
geometries.

For example, the following SQL query

.. code::

    select * from chicago where st_contains(st_makeBBOX(0.0, 0.0, 90.0, 90.0), geom)

uses two UDFs--``st_contains`` and ``st_makeBBOX``--to find the rows in the ``chicago``
``DataFrame`` where column ``geom`` is contained within the specified bounding box.

A complete list of the implemented UDFs is given in the next section (:doc:`./sparksql_functions`).

.. _classes representing geometry objects: http://docs.geotools.org/stable/userguide/library/jts/geometry.html

.. _OpenGIS Simple feature access common architecture: http://www.opengeospatial.org/standards/sfa

.. _OpenGIS Simple feature access SQL option: http://www.opengeospatial.org/standards/sfs