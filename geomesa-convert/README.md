A configurable and extensible library for converting data into SimpleFeatures

## Overview

Converters for various different data formats can be configured and instantiated using the 
```SimpleFeatureConverters``` factory and a target ```SimpleFeatureType```. Currently available converters are: 

 * delimited text
 * fixed width
 * avro
 * json
 * xml
    
The converter allows the specification of fields extracted from the data and transformations on those fields.  Syntax of 
transformations is very much like ```awk``` syntax.  Fields with names that correspond to attribute names in the 
```SimpleFeatureType``` will be directly populated in the result SimpleFeature.  Fields that do not align with 
attributes in the ```SimpleFeatureType``` are assumed to be intermediate fields used for deriving attributes.  Fields 
can reference other fields by name for building up complex attributes.

## Example usage

Suppose you have a ```SimpleFeatureType``` with the following schema: ```phrase:String,dtg:Date,geom:Point:srid=4326``` 
and comma-separated data as shown below.

    first,hello,2015-01-01T00:00:00.000Z,45.0,45.0
    second,world,2015-01-01T00:00:00.000Z,45.0,45.0                                                                                                                                                                    

The first two fields should be concatenated together to form the phrase, the third field should be parsed as a date, and
the last two fields should be formed into a ```Point``` geometry.  The following configuration file defines an 
appropriate converter for taking this csv data and transforming it into our ```SimpleFeatureType```.  

     converter = { 
      type         = "delimited-text",
      format       = "CSV",
      id-field     = "md5($0)",
      fields = [
        { name = "phrase", transform = "concat($1, $2)" },
        { name = "lat",    transform = "$4::double" },
        { name = "lon",    transform = "$5::double" },
        { name = "dtg",    transform = "dateHourMinuteSecondMillis($3)" },
        { name = "geom",   transform = "point($lon, $lat)" }
      ]
     }

The ```id``` of the ```SimpleFeature``` is formed from an md5 hash of the entire record (```$0``` is the original data) 
and the other fields are formed from appropriate transforms.

## Transformation functions

Provided transformation functions are listed below.

### String functions
 * ```stripQuotes```
 * ```strlen```
 * ```trim```
 * ```capitalize```
 * ```lowercase```
 * ```regexReplace```
 * ```concat```
 * ```substr```
 * ```toString```
 
### Date functions
 * ```now```
 * ```date```
 * ```datetime```
 * ```isodate``` 
 * ```isodatetime```
 * ```basicDateTimeNoMillis```
 * ```dateHourMinuteSecondMillis```
 * ```millisToDate```

### Geometry functions
  * ```point```
  * ```linestring```
  * ```polygon```
  * ```geometry```
  
### ID Functions
 * ```string2bytes```
 * ```md5```
 * ```uuid```
 * ```base64```
 
### Data Casting
 * ```::int```
 * ```::long```
 * ```::float```
 * ```::double```
 * ```::boolean```
 * ```::r```

### List and Map Parsing
 * ```parseList```
 * ```parseMap```
 
### JSON/Avro Transformations

See Parsing Json and Parsing Avro sections
  
## Transformation Function Usage

### String functions
#### ```stripQuotes``` - Remove double quotes from a string

Usage: ```stripQuotes($1)```

Example: ```stripQuotes('fo"o') = foo```

#### ```strlen``` - Returns length of a string

Usage: ```strlen($1)```

Example: ```strlen('foo') = 3```

#### ```trim``` - Trim whitespace from around a string

Usage: ```trim($1)```

Example: ```trim('  foo ') = foo```

#### ```capitalize``` - Capitalize a string

Usage: ```capitalize($1)```

Example: ```capitalize('foo') = Foo```

#### ```lowercase``` - Lowercase a string

Usage: ```lowercase($1)```

Example: ```lowercase('FOO') = foo```

#### ```uppercase``` - Uppercase a string

Usage: ```uppercase($1)```

Example: ```uppercase('foo') = FOO```

#### ```regexReplace``` - Replace a given pattern with a target pattern in a string

Usage: ```regexReplace($regex, $replacement, $1)```

Example: ```regexReplace('foo'::r, 'bar', 'foobar') = barbar```

#### ```concat``` - Concatenate two strings

Usage: ```concat($0, $1)```

Example: ```concat('foo', 'bar') = foobar```

#### ```substr``` - Return the substring of a string

Usage: ```substr($1, $startIndex, $endIndex)```

Example: ```substr('foobarbaz', 2, 5) = oba```

#### ```toString``` - Convert another datatype to a string

Usage: ```toString($0)```

Example: ```concat(toString(5), toString(6)) = '56'```
 
### Date functions

#### ```now``` - Use the current system time

Usage: ```now()```


#### ```date``` - Custom date parser

Usage: ```date($format, $1)```

Example: ```date('YYYY-MM-dd\'T\'HH:mm:ss.SSSSSS', '2015-01-01T00:00:00.000000')```
  
  
#### ```datetime``` - A strict ISO 8601 Date parser for format yyyy-MM-dd'T'HH:mm:ss.SSSZZ
 
Usage: ```datetime($1)```

Example: ```datetime('2015-01-01T00:00:00.000Z')```

#### ```isodate``` -  A basic date format for yyyyMMdd

Usage: ```isodate($1)```

Example: ```isodate('20150101')```

#### ```isodatetime``` -  A basic format that combines a basic date and time for format yyyyMMdd'T'HHmmss.SSSZ

Usage: ```isodatetime($1)```

Example: ```isodatetime('20150101T000000.000Z')```

#### ```basicDateTimeNoMillis``` - A basic format that combines a basic date and time with no millis for format yyyyMMdd'T'HHmmssZ

Usage: ```basicDateTimeNoMillis($1)```

Example: ```basicDateTimeNoMillis('20150101T000000Z')```

#### ```dateHourMinuteSecondMillis``` - Formatter for full date, and time keeping the first 3 fractional seconds for format yyyy-MM-dd'T'HH:mm:ss.SSS

Usage: ```dateHourMinuteSecondMillis($1)```

Example: ```dateHourMinuteSecondMillis('2015-01-01T00:00:00.000')``` 

#### ```millisToDate``` - Create a new date from as long representing millis since 1970

Usage: ```millisToDate($1)```

Example: ```millisToDate(1449675054462::long)``` 

### Geometry functions

#### ```point``` - Parse a Point geometry from lon/lat or WKT

Usage: ```point($lon, $lat)``` or ```point($wkt)```

Note: Ordering is important here...GeoMesa defaults to longitude first

Example: Parsing lon/lat from JSON:

Parsing lon/lat

    # config
    { name = "lon", json-type="double", path="$.lon" }
    { name = "lat", json-type="double", path="$.lat" }
    { name = "geom", transform="point($lon, $lat)" }
    
    # data
    {
        "lat": 23.9,
        "lon": 24.2,
    }
    
Example: Parsing lon/lat from text without creating lon/lat fields:

    # config
    { name = "geom", transform="point($2::double, $3::double)"
    
    # data
    id,lat,lon,date
    identity1,23.9,24.2,2015-02-03
    
Example: Parsing WKT as a point

    # config
    { name = "geom", transform="point($2)" }
    
    # data 
    ID,wkt,date
    1,POINT(2 3),2015-01-02

#### ```linestring``` - Parse a linestring from a WKT string

Usage: ```linestring($0)```

Example: ```linestring('LINESTRING(102 0, 103 1, 104 0, 105 1)')```

#### ```polygon``` - Parse a polygon from a WKT string

Usage: ```polygon($0)```

Example: ```polygon('polygon((100 0, 101 0, 101 1, 100 1, 100 0))')```
  
#### ```geometry``` - Parse a geometry from a WKT string or GeoJson
  
Example: Parsing WKT as a geometry

    # config
    { name = "geom", transform="geometry($2)" }
    
    # data 
    ID,wkt,date
    1,POINT(2 3),2015-01-02
    
Example: Parsing GeoJson geometry

    # config
    { name = "geom", json-type = "geometry", path = "$.geometry" }
    
    # data
    {
        id: 1,
        number: 123,
        color: "red",
        "geometry": {"type": "Point", "coordinates": [55, 56]}
    }
    
### ID Functions

#### ```string2bytes``` - Converts a string to a UTF-8 byte array

#### ```md5``` - Creates an MD5 hash from a byte array

Usage: ```md5($0)```

Example: ```md5(string2bytes('row,of,data'))```

#### ```uuid``` - Generates a random UUID

Usage: ```uuid()```

#### ```base64``` - Encodes a byte array as a base-64 string

Usage; ```base64($0)```

Example: ```base64(string2bytes('foo'))```
 
### Data Casting
 * ```::int```
 * ```::long```
 * ```::float```
 * ```::double```
 * ```::boolean```
 * ```::r``` - Converts a string to a Regex object

### List and Map Parsing
#### ```parseList``` - Parse a List[T] type from a string
 
If your SimpleFeatureType config contains a list or map you can easily configure a transform function to parse it using
the ```parseList``` function which takes either 2 or 3 args

 1. The primitive type of the list (int, string, double, float, boolean, etc)
 2. The reference to parse
 3. Optionally, the list delimiter (defaults to a comma)

Here's some sample CSV data:

    ID,Name,Age,LastSeen,Friends,Lat,Lon
    23623,Harry,20,2015-05-06,"Will, Mark, Suzan",-100.236523,23
    26236,Hermione,25,2015-06-07,"Edward, Bill, Harry",40.232,-53.2356
    3233,Severus,30,2015-10-23,"Tom, Riddle, Voldemort",3,-62.23

For example, an SFT may specific a field:

    { name = "friends", type = "List[String]" }

And a transform to parse the quoted CSV field:

    { name = "friends", transform = "parseList('string', $5)" }

#### ```parseMap```

Parsing Maps is similar. Take for example this CSV data with a quoted map field:

    1,"1->a,2->b,3->c,4->d",2013-07-17,-90.368732,35.3155
    2,"5->e,6->f,7->g,8->h",2013-07-17,-70.970585,42.36211
    3,"9->i,10->j",2013-07-17,-97.599004,30.50901

Our field type is:

    numbers:Map[Integer,String]

Then we specify a transform:

    { name = "numbers", transform = "parseMap('int -> string', $2)" }

Optionally we can also provide custom list/record and key-value delimiters for a map:

      { name = "numbers", transform = "parseMap('int -> string', $2, ',', '->')" }

## Parsing JSON

The JSON converter defines the path to a list of features as well as json-types of each field:

    converter = {
      type         = "json"
      id-field     = "$id"
      feature-path = "$.Features[*]"
      fields = [
        { name = "id",     json-type = "integer",  path = "$.id",               transform = "toString($0)" }
        { name = "number", json-type = "integer",  path = "$.number",                                      }
        { name = "color",  json-type = "string",   path = "$.color",            transform = "trim($0)"     }
        { name = "weight", json-type = "double",   path = "$.physical.weight",                             }
        { name = "geom",   json-type = "geometry", path = "$.geometry",                                    }
      ]
    }

#### JSON Geometries

Geometry objects can be represented as either WKT or GeoJSON and parsed with the same config:

Config:

     { name = "geom", json-type = "geometry", path = "$.geometry", transform = "point($0)" }

Data:

    {
       DataSource: { name: "myjson" },
       Features: [
         {
           id: 1,
           number: 123,
           color: "red",
           geometry: { "type": "Point", "coordinates": [55, 56] }
         },
         {
           id: 2,
           number: 456,
           color: "blue",
           geometry: "Point (101 102)"
         }
       ]
    }

Remember to use the most general Geometry type as your ```json-type``` or SimpleFeatureType field type. Defining a type
```Geometry``` allows for polygons, points, and linestrings, but specifying a specific geometry like point will only
 allow for parsing of points.
 
## Parsing Avro

The Avro parsing library is similar to the JSON parsing library. For this example we'll use the following avro schema
in a file named ```/tmp/schema.avsc```:

    {
      "namespace": "org.locationtech",
      "type": "record",
      "name": "CompositeMessage",
      "fields": [
        { "name": "content",
          "type": [
             {
               "name": "DataObj",
               "type": "record",
               "fields": [
                 {
                   "name": "kvmap",
                   "type": {
                      "type": "array",
                      "items": {
                        "name": "kvpair",
                        "type": "record",
                        "fields": [
                          { "name": "k", "type": "string" },
                          { "name": "v", "type": ["string", "double", "int", "null"] }
                        ]
                      }
                   }
                 }
               ]
             },
             {
                "name": "OtherObject",
                "type": "record",
                "fields": [{ "name": "id", "type": "int"}]
             }
          ]
       }
      ]
    }
    
This schema defines an avro file that has a field named ```content``` which has a nested object which is either of type
```DataObj``` or ```OtherObject```. As an exercise...using avro tools we can generate some test data and view it:

    java -jar /tmp/avro-tools-1.7.7.jar random --schema-file /tmp/schema -count 5 /tmp/avro 
    
    $ java -jar /tmp/avro-tools-1.7.7.jar tojson /tmp/avro
    {"content":{"org.locationtech.DataObj":{"kvmap":[{"k":"thhxhumkykubls","v":{"double":0.8793488185997134}},{"k":"mlungpiegrlof","v":{"double":0.45718223406586045}},{"k":"mtslijkjdt","v":null}]}}}
    {"content":{"org.locationtech.OtherObject":{"id":-86025408}}}
    {"content":{"org.locationtech.DataObj":{"kvmap":[]}}}
    {"content":{"org.locationtech.DataObj":{"kvmap":[{"k":"aeqfvfhokutpovl","v":{"string":"kykfkitoqk"}},{"k":"omoeoo","v":{"string":"f"}}]}}}
    {"content":{"org.locationtech.DataObj":{"kvmap":[{"k":"jdfpnxtleoh","v":{"double":0.7748286862915655}},{"k":"bueqwtmesmeesthinscnreqamlwdxprseejpkrrljfhdkijosnogusomvmjkvbljrfjafhrbytrfayxhptfpcropkfjcgs","v":{"int":-1787843080}},{"k":"nmopnvrcjyar","v":null},{"k":"i","v":{"string":"hcslpunas"}}]}}}

Here's a more relevant sample record:

    {
      "content" : {
        "org.locationtech.DataObj" : {
          "kvmap" : [ {
            "k" : "lat",
            "v" : {
              "double" : 45.0
            }
          }, {
            "k" : "lon",
            "v" : {
              "double" : 45.0
            }
          }, {
            "k" : "prop3",
            "v" : {
              "string" : " foo "
            }
          }, {
            "k" : "prop4",
            "v" : {
              "double" : 1.0
            }
          } ]
        }
      }
    }

Let's say we want to convert our avro array of kvpairs into a simple feature. We notice that there are 4 attributes:

 * lat
 * lon
 * prop3
 * prop4
 
We can define a converter config to parse the avro:

    converter = {
      type   = "avro"
      schema = "/tmp/schema.avsc"
      sft    = "testsft"
      id-field = "uuid()"
      fields = [
        { name = "tobj", transform = "avroPath($1, '/content$type=DataObj')" },
        { name = "lat",  transform = "avroPath($tobj, '/kvmap[$k=lat]/v')" },
        { name = "lon",  transform = "avroPath($tobj, '/kvmap[$k=lon]/v')" },
        { name = "geom", transform = "point($lon, $lat)" }
      ]
    }

### AvroPath

GeoMesa Convert allows users to define "avropaths" to the data similar to a jsonpath or xpath. This AvroPath allows you 
to extract out fields from avro records into SFT fields.

#### ```avroPath``` - Extract values from nested Avro structures 
 
Usage: ```avroPath($ref, $pathString)```

* ```$ref``` - a reference object (avro root or extracted object)
* ```pathString``` - forward-slash delimited path strings. paths are field names with modifiers:
  * ```$type=<typename>``` - interpret the field name as an avro schema type
  * ```[$<field>=<value>]``` - select records with a field named "field" and a value equal to "value"

## Extending the converter library

There are two ways to extend the converter library - adding new transformation functions and adding new data formats.

### Adding new transformation functions

To add new transformation functions, create a ```TransformationFunctionFactory``` and register it in 
```META-INF/services/org.locationtech.geomesa.convert.TransformationFunctionFactory```.  For example, here's how to add 
a new transformation function that computes a SHA-256 hash.

```scala
import org.locationtech.geomesa.convert.TransformerFunctionFactory
import org.locationtech.geomesa.convert.TransformerFn

class SHAFunctionFactory extends TransformerFunctionFactory {
  override def functions = Seq(sha256fn)
  val sha256fn = TransformerFn("sha256") { args =>
    Hashing.sha256().hashBytes(args(0).asInstanceOf[Array[Byte]])
  }
}
```

The ```sha256``` function can then be used in a field as shown.

```
   fields: [
      { name = "hash", transform = "sha256(string2bytes($0))" }
   ]
```

### Adding new data formats

To add new data formats, implement the ```SimpleFeatureConverterFactory``` and ```SimpleFeatureConverter``` interfaces 
and register them in ```META-INF/services``` appropriately.  See 
```org.locationtech.geomesa.convert.avro.Avro2SimpleFeatureConverter``` for an example.

## Example Using GeoMesa Tools

The following example can be used with geomesa tools:

    geomesa ingest -u <user> -p <pass> -i <instance> -z <zookeepers> -conf example.conf example.csv 

Sample csv file: ```example.csv```

    ID,Name,Age,LastSeen,Friends,Lat,Lon
    23623,Harry,20,2015-05-06,"Will, Mark, Suzan",-100.236523,23
    26236,Hermione,25,2015-06-07,"Edward, Bill, Harry",40.232,-53.2356
    3233,Severus,30,2015-10-23,"Tom, Riddle, Voldemort",3,-62.23
    
A config file: ```example.conf```

    sft = {
      type-name = "renegades"
      attributes = [
        { name = "id", type = "Integer", index = false },
        { name = "name", type = "String", index = true },
        { name = "age", type = "Integer", index = false },
        { name = "lastseen", type = "Date", index = true },
        { name = "friends", type = "List[String]", index = true },
        { name = "geom", type = "Point", index = true, srid = 4326, default = true }
      ]
    },
    converter = {
      type = "delimited-text",
      format = "CSV",
      options {
        skip-lines = 1
      },
      id-field = "toString($id)",
      fields = [
        { name = "id", transform = "$1::int" },
        { name = "name", transform = "$2::string" },
        { name = "age", transform = "$3::int" },
        { name = "lastseen", transform = "$4::date" },
        { name = "friends", transform = "parseList('string', $5)" },
        { name = "lon", transform = "$6::double" },
        { name = "lat", transform = "$7::double" },
        { name = "geom", transform = "point($lon, $lat)" }
      ]
    }
