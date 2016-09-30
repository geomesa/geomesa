HBase/Bigtable Data Stores
==========================

HBase
-----
The code for the HBase support is found in two modules in the source distribution.

* ``geomesa-hbase/geomesa-hbase-datastore`` - the core GeoMesa HBase datastore
* ``geomesa-hbase/geomesa-hbase-gs-plugin`` - the GeoServer bundle for GeoMesa HBase

HBase Data Store
~~~~~~~~~~~~~~~~

An instance of an HBase data store can be obtained through the normal GeoTools discovery methods, assuming that the GeoMesa code is on the classpath. The HBase data store also requires that an ``hbase-site.xml`` be located on the classpath; the connection parameters for the HBase data store, including ``hbase.zookeeper.quorum`` and ``hbase.zookeeper.property.clientPort``, are obtained from this file.

.. code-block:: java

    Map<String, Serializable> parameters = new HashMap<>;
    parameters.put("bigtable.table.name", "geomesa");
    parameters.put("namespace", "http://example.com");
    org.geotools.data.DataStore dataStore =
        org.geotools.data.DataStoreFinder.getDataStore(parameters);

The data store takes two parameters:

* ``bigtable.table.name`` - the name of the HBase table that stores feature type data
* ``namespace`` - the namespace URI for the data store (optional)

More information on using GeoTools can be found in the GeoTools `user guide
<http://docs.geotools.org/stable/userguide/>`__.

Using the HBase Data Store in GeoServer
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

See :doc:`./geoserver`.

Bigtable
--------

Experimental support for GeoMesa implemented on `Google Cloud Bigtable <https://cloud.google.com/bigtable>`__.

The code for Bigtable support is found in two modules in the source distribution:

* ``geomesa-hbase/geomesa-bigtable-datastore`` - contains a stub POM for building a Google Cloud Bigtable-backed GeoTools data store
* ``geomesa-hbase/geomesa-bigtable-gs-plugin`` - contains a stub POM for building a bundle containing all of the JARs to use the Google Cloud Bigtable data store in GeoServer
