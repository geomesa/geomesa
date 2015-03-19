/*
 * Copyright 2015 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.jobs.interop.mapred;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.Counters.Counter;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.opengis.feature.simple.SimpleFeature;

import java.io.IOException;
import java.util.HashMap;

/**
 * Sample job showing how to read features using GeoMesaInputFormat
 */
public class FeatureCountJob {

    public static class Map extends MapReduceBase implements Mapper<Text, SimpleFeature, Text, Text> {

        static enum CountersEnum { FEATURES }

        public void map(Text key,
                        SimpleFeature value,
                        OutputCollector<Text, Text> output,
                        Reporter reporter) throws IOException {
            Counter counter = reporter.getCounter(CountersEnum.class.getName(),
                                                  CountersEnum.FEATURES.toString());
            counter.increment(1);
            output.collect(new Text(value.getID()), new Text(value.getAttribute("geom").toString()));
        }
    }

    public static void main(String[] args) throws Exception {
        JobConf conf = new JobConf(FeatureCountJob.class);
        conf.setJobName("simple feature count");

        conf.setOutputKeyClass(Text.class);
        conf.setOutputValueClass(Text.class);

        conf.setMapperClass(Map.class);
        conf.setNumReduceTasks(0);

        conf.setInputFormat(GeoMesaInputFormat.class);
        conf.setOutputFormat(TextOutputFormat.class);

        FileOutputFormat.setOutputPath(conf, new Path("/tmp/myjob"));

        java.util.Map<String, String> params = new HashMap<String, String>();
        params.put("instanceId", "myinstance");
        params.put("zookeepers", "zoo1,zoo2,zoo3");
        params.put("user", "myuser");
        params.put("password", "mypassword");
        params.put("tableName", "mycatalog");

        String cql = "BBOX(geom, -165,5,-50,75) AND dtg DURING 2015-03-02T00:00:00.000Z/2015-03-02T23:59:59.999Z";

        GeoMesaInputFormat.configure(conf, params, "myfeature", cql);

        JobClient.runJob(conf);
    }
}
