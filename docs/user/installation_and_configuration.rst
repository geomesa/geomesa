Installation and Configuration
==============================

Prerequisites and Platform
--------------------------

.. warning::

    GeoMesa requires Accumulo (http://accumulo.apache.org/) |accumulo_version|, which in turn
    requires Hadoop (http://hadoop.apache.org/) |hadoop_version| and ZooKeeper (http://zookeeper.apache.org) 
    |zookeeper_version|. Installing and configuring Accumulo is beyond the scope of this manual.

    Using the Kafka (http://kafka.apache.org/) module requires Kafka |kafka_version| and ZooKeeper |zookeeper_version|.  

To install the binary distribution:

* Java JRE or JDK 7

To build and install the source distribution:

* Java JDK 7
* Apache Maven (http://maven.apache.org/) |maven_version|
* A ``git`` client (http://git-scm.com/)

Versions and Downloads
----------------------

.. note::

    The current recommended version is |release|.

**Latest release**: |release|

.. TODO: substitutions don't work in some kinds of markup, including URLs

* Release tarball: |release_tarball|
* Source: |release_source_tarball|

**Development version (source only)**: |development|

* Source: https://github.com/locationtech/geomesa/archive/master.tar.gz

**1.1.x release**: |release_1_1|

* Release tarball: |release_1_1_tarball|
* Source: |release_1_1_source_tarball|

GeoMesa artifacts can be downloaded from the LocationTech Maven repository: https://repo.locationtech.org/content/repositories/geomesa-releases/.

Snapshot artifacts are available in the LocationTech snapshots repository: https://repo.locationtech.org/content/repositories/geomesa-snapshots/.

.. _install_binary:

Installing from the Binary Distribution
---------------------------------------

GeoMesa artifacts are available for download or can be built from source. 
The easiest way to get started is to download the most recent binary version (``$VERSION`` = |release|) 
and untar it somewhere convenient.

.. code-block:: bash

    # download and unpackage the most recent distribution
    $ wget http://repo.locationtech.org/content/repositories/geomesa-releases/org/locationtech/geomesa/geomesa-dist/$VERSION/geomesa-dist-$VERSION-bin.tar.gz
    $ tar xvf geomesa-dist-$VERSION-bin.tar.gz
    $ cd geomesa-$VERSION
    $ ls
    dist/  docs/  LICENSE.txt  README.md

.. _building_source:

Building from Source
--------------------

GeoMesa may also be built from source. For more information refer to the
**GeoMesa Developer Manual**, or to the ``README.md`` file in the the
source distribution. The remainder of the instructions in this chapter assume
the use of the binary GeoMesa distribution. If you have built from source, the
distribution is created in the ``geomesa-dist/target`` directory as a part of
the build process.

More information about developing with GeoMesa may be found in the 
**GeoMesa Developer Manual**. 

.. _setting_up_commandline:

Setting up the Command Line Tools
---------------------------------

GeoMesa comes with a set of command line tools for managing features. To complete the setup 
of the tools, cd into the ``dist/tools`` directory of the binary distribution and unpack the
``geomesa-tools-$VERSION-bin.tar.gz`` file (``$VERSION`` = |release|).

.. code-block:: bash

    $ cd geomesa-$VERSION/dist/tools
    $ tar -xzvf geomesa-tools-$VERSION-bin.tar.gz
    $ cd geomesa-tools-$VERSION
    $ ls
    bin/  conf/  examples/  lib/

The instructions below assume that the ``geomesa-tools-$VERSION`` directory is kept in the 
``geomesa-$VERSION/dist/tools`` directory, but the tools distribution may be moved elsewhere
as desired.

In the ``geomesa-tools-$VERSION`` directory, run ``bin/geomesa configure`` to set up the tools.

.. code-block:: bash

    ### in geomesa-$VERSION/dist/tools/geomesa-tools-$VERSION:
    $ bin/geomesa configure
    Warning: GEOMESA_HOME is not set, using /path/to/geomesa-$VERSION/dist/tools/geomesa-tools-$VERSION
    Using GEOMESA_HOME as set: /path/to/geomesa-$VERSION/dist/tools/geomesa-tools-$VERSION
    Is this intentional? Y\n y
    Warning: GEOMESA_LIB already set, probably by a prior configuration.
    Current value is /path/to/geomesa-$VERSION/dist/tools/geomesa-tools-$VERSION/lib.

    Is this intentional? Y\n y

    To persist the configuration please update your bashrc file to include: 
    export GEOMESA_HOME=/path/to/geomesa-$VERSION/dist/tools/geomesa-tools-$VERSION
    export PATH=${GEOMESA_HOME}/bin:$PATH

Update and re-source your ``~/.bashrc`` file to include the ``$GEOMESA_HOME`` and ``$PATH`` updates.


.. warning::

    Please note that the ``$GEOMESA_HOME`` variable points to the location of the ``geomesa-tools-$VERSION``
    directory, not the main geomesa binary distribution directory!

Due to licensing restrictions, dependencies for shape file support and raster 
ingest must be separately installed. These may be installed by invoking the
following commands: 

.. code-block:: bash

    $ bin/install-jai
    $ bin/install-jline
    $ bin/install-vecmath

Finally, test your installation by editing the ``bin/test-geomesa`` file with configuration
data specific to your setup and running it: 

.. code-block:: bash

    $ bin/test-geomesa

Test the GeoMesa Tools:

.. code-block:: bash

    $ geomesa
    Using GEOMESA_HOME = /path/to/geomesa-$VERSION
    Usage: geomesa [command] [command options]
      Commands:
        create           Create a feature definition in a GeoMesa catalog
        deletecatalog    Delete a GeoMesa catalog completely (and all features in it)
        deleteraster     Delete a GeoMesa Raster Table
        describe         Describe the attributes of a given feature in GeoMesa
        explain          Explain how a GeoMesa query will be executed
        export           Export a GeoMesa feature
        getsft           Get the SimpleFeatureType of a feature
        help             Show help
        ingest           Ingest a file of various formats into GeoMesa
        ingestraster     Ingest a raster file or raster files in a directory into GeoMesa
        list             List GeoMesa features for a given catalog
        querystats       Export queries and statistics about the last X number of queries to a CSV file.
        removeschema     Remove a schema and associated features from a GeoMesa catalog
        tableconf        Perform table configuration operations
        version          GeoMesa Version


GeoMesa Tools comes bundled by default with an SLF4J implementation that is installed to the ``$GEOMESA_HOME/lib`` directory
named ``slf4j-log4j12-1.7.5.jar``. If you already have an SLF4J implementation installed on your Java classpath you may
see errors at runtime and will have to exclude one of the JARs. This can be done by simply renaming the bundled
``slf4j-log4j12-1.7.5.jar`` file to ``slf4j-log4j12-1.7.5.jar.exclude``.
 
Note that if no slf4j implementation is installed you will see this error:

.. code::

    SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
    SLF4J: Defaulting to no-operation (NOP) logger implementation
    SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.

In this case you may download SLF4J from http://www.slf4j.org/download.html. Extract 
``slf4j-log4j12-1.7.7.jar`` and place it in the ``lib`` directory of the binary distribution. 
If this conflicts with another SLF4J implementation, it may need to be removed from the lib directory.


Installing the Accumulo distributed runtime library
---------------------------------------------------

The ``geomesa-$VERSION/dist/accumulo`` directory contains the distributed
runtime jar that should be copied into the ``$ACCUMULO_HOME/lib/ext`` folder on
each tablet server. This jar contains the GeoMesa Accumulo iterators that are
necessary to query GeoMesa.

.. code-block:: bash

    # something like this for each tablet server
    $ scp geomesa-$VERSION/dist/accumulo/geomesa-accumulo-distributed-runtime-$VERSION.jar tserver1:$ACCUMULO_HOME/lib/ext

.. _install_geoserver_plugins:

Installing the GeoMesa GeoServer plugins
----------------------------------------

.. warning::

    The GeoMesa GeoServer plugins require the use of GeoServer
    |geoserver_version| and GeoTools |geotools_version|.


As described in section :ref:`geomesa_and_geoserver` , GeoMesa implements a
GeoTools-compatible (http://geotools.org/) data store. This makes it possible
to use GeoMesa as a data store in GeoServer (http://geoserver.org/). The documentation
below describes how to configure GeoServer to connect to GeoMesa Accumulo and Kafka data stores.
The installation and setup of GeoServer is beyond the scope of this document, but
instructions may be found here: http://docs.geoserver.org/latest/en/user/installation/index.html

After GeoServer is running, you will also need to install the WPS plugin to your GeoServer
instance. The GeoServer WPS Plugin (available at 
http://docs.geoserver.org/stable/en/user/extensions/wps/install.html) must match the version of
GeoServer instance. This is needed for both the Accumulo and Kafka variants of the plugin.

For Accumulo
^^^^^^^^^^^^

To install the GeoMesa Accumulo GeoServer plugin, unpack the contents of the
``geomesa-accumulo-gs-plugin-$VERSION.zip`` file in ``geomesa-$VERSION/dist/gs-plugins`` 
into your GeoServer's ``lib`` directory (``$VERSION`` = |release|):

If you are using Tomcat:

.. code-block:: bash

    $ unzip \
      geomesa-$VERSION/dist/gs-plugins/geomesa-accumulo-gs-plugin-$VERSION-install.zip \
      -d /path/to/tomcat/webapps/geoserver/WEB-INF/lib/

If you are using GeoServer's built in Jetty web server:

.. code-block:: bash

    $ unzip \
      geomesa-$VERSION/dist/gs-plugins/geomesa-accumulo-gs-plugin-$VERSION-install.zip \
      -d /path/to/geoserver/webapps/geoserver/WEB-INF/lib/

There are additional JARs for Accumulo, Zookeeper, Hadoop, and Thrift that will
be specific to your installation that you will also need to copy to GeoServer's
``WEB-INF/lib`` directory. For example, GeoMesa only requires Hadoop
|hadoop_version|, but if you are using Hadoop 2.5.0 you should use the JARs
that match the version of Hadoop you are running.

There is a script in the ``geomesa-tools-$VERSION`` directory
(``$GEOMESA_HOME/bin/install-hadoop-accumulo.sh``) which will install these
dependencies to a target directory using ``wget`` (requires an internet
connection). You may have to edit this file to set the versions of Accumulo,
Zookeeper, Hadoop, and Thrift you are running.

.. code-block:: bash

    $ $GEOMESA_HOME/bin/install-hadoop-accumulo.sh /path/to/tomcat/webapps/geoserver/WEB-INF/lib/
    Install accumulo and hadoop dependencies to /path/to/tomcat/webapps/geoserver/WEB-INF/lib/?
    Confirm? [Y/n]y
    fetching https://search.maven.org/remotecontent?filepath=org/apache/accumulo/accumulo-core/1.6.2/accumulo-core-1.6.2.jar
    --2015-09-29 15:06:48--  https://search.maven.org/remotecontent?filepath=org/apache/accumulo/accumulo-core/1.6.2/accumulo-core-1.6.2.jar
    Resolving search.maven.org (search.maven.org)... 207.223.241.72
    Connecting to search.maven.org (search.maven.org)|207.223.241.72|:443... connected.
    HTTP request sent, awaiting response... 200 OK
    Length: 4646545 (4.4M) [application/java-archive]
    Saving to: ‘/path/to/tomcat/webapps/geoserver/WEB-INF/lib/accumulo-core-1.6.2.jar’
    ...

If you do no have an internet connection you can download the JARs manually via http://search.maven.org/.
These may include the JARs below; the specific JARs are included only for reference (assuming Accumulo 1.6.2,
Zookeeper 3.4.5, Hadoop 2.2.0 and Thrift 0.9.1):

* Accumulo
    * accumulo-core-1.6.2.jar
    * accumulo-fate-1.6.2.jar
    * accumulo-trace-1.6.2.jar
* Zookeeper
    * zookeeper-3.4.5.jar
* Hadoop
    * hadoop-auth-2.2.0.jar
    * hadoop-client-2.2.0.jar
    * hadoop-common-2.2.0.jar
    * hadoop-hdfs-2.2.0.jar
    * hadoop-mapreduce-client-app-2.2.0.jar
    * hadoop-mapreduce-client-common-2.2.0.jar
    * hadoop-mapreduce-client-core-2.2.0.jar
    * hadoop-mapreduce-client-jobclient-2.2.0.jar
    * hadoop-mapreduce-client-shuffle-2.2.0.jar
* Thrift
    * libthrift-0.9.1.jar
    
There are also GeoServer JARs that may need to be updated for Accumulo (also in the ``lib`` directory):
    
* **commons-configuration**: Accumulo requires commons-configuration 1.6 and previous versions should be replaced [`commons-configuration-1.6.jar <https://search.maven.org/remotecontent?filepath=commons-configuration/commons-configuration/1.6/commons-configuration-1.6.jar>`_]
* **commons-lang**: GeoServer ships with commons-lang 2.1, but Accumulo requires replacing that with version 2.4 [`commons-lang-2.4.jar <https://search.maven.org/remotecontent?filepath=commons-lang/commons-lang/2.4/commons-lang-2.4.jar>`_]

After placing the dependencies in the correct folder, be sure to restart GeoServer for changes to take place.

For Kafka
^^^^^^^^^

To install the GeoMesa Kafka GeoServer plugin, unpack the contents of the
``geomesa-kafka-gs-plugin-$VERSION.zip`` file in ``geomesa-$VERSION/dist/gs-plugins`` 
into your GeoServer's ``lib`` directory (``$VERSION`` = |release|):

If you are using Tomcat:

.. code-block:: bash

    $ unzip \
      geomesa-$VERSION/dist/gs-plugins/geomesa-kafka-gs-plugin-$VERSION-install.zip \
      -d /path/to/tomcat/webapps/geoserver/WEB-INF/lib/

If you are using GeoServer's built in Jetty web server:

.. code-block:: bash

    $ unzip \
      geomesa-$VERSION/dist/gs-plugins/geomesa-kafka-gs-plugin-$VERSION-install.zip \
      -d /path/to/geoserver/webapps/geoserver/WEB-INF/lib/

Then copy these dependencies (or the equivalents for your Kafka installation) to
your ``WEB-INF/lib`` directory.

* Kafka
    * kafka-clients-0.8.2.1.jar
    * kafka_2.10-0.8.2.1.jar
    * metrics-core-2.2.0.jar
    * zkclient-0.3.jar
* Zookeeper
    * zookeeper-3.4.5.jar

Note: when using the Kafka Data Store with GeoServer in Tomcat it will most likely be necessary to increase the memory settings for Tomcat:

.. code-block:: bash

    export CATALINA_OPTS="-Xms512M -Xmx1024M -XX:PermSize=256m -XX:MaxPermSize=256m"

After placing the dependencies in the correct folder, be sure to restart GeoServer for changes to take place.

Upgrading
---------

To upgrade between minor releases of GeoMesa, the versions of all GeoMesa components **must** match. 

This means that the version of the ``geomesa-distributed-runtime`` JAR installed on Accumulo tablet servers
**must** match the version of the ``geomesa-plugin`` JARs installed in the ``WEB-INF/lib`` directory of GeoServer.

Configuring GeoServer
---------------------

Depending on your hardware, it may be important to set the limits for
your WMS plugin to be higher or disable them completely by clicking
"WMS" under "Services" on the left side of the admin page of GeoServer.
Check with your server administrator to determine the correct settings.
For massive queries, the standard 60 second timeout may be too short.

|"Disable limits"|

.. |"Disable limits"| image:: _static/img/wms_limits.png
