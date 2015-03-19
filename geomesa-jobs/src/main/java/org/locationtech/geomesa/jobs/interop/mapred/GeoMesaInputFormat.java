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

package org.locationtech.geomesa.jobs.interop.mapred;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.locationtech.geomesa.jobs.mapred.GeoMesaInputFormat$;
import org.opengis.feature.simple.SimpleFeature;
import scala.Option;
import scala.Predef;
import scala.Tuple2;
import scala.collection.JavaConverters;

import java.io.IOException;
import java.util.Map;

/**
 * Input format that will read simple features from GeoMesa based on a CQL query.
 * The key will be the feature ID. Configure using the static methods.
 */
public class GeoMesaInputFormat implements InputFormat<Text, SimpleFeature> {

    private org.locationtech.geomesa.jobs.mapred.GeoMesaInputFormat delegate =
            new org.locationtech.geomesa.jobs.mapred.GeoMesaInputFormat();

    @Override
    public InputSplit[] getSplits(JobConf job, int numSplits) throws IOException {
        return delegate.getSplits(job, numSplits);
    }

    @Override
    public RecordReader<Text, SimpleFeature> getRecordReader(InputSplit split, JobConf job, Reporter reporter)
            throws IOException {
        return delegate.getRecordReader(split, job, reporter);
    }

    public static void configure(JobConf job,
                                 Map<String, String> dataStoreParams,
                                 String featureTypeName,
                                 String filter) {
        scala.collection.immutable.Map<String, String> scalaParams =
                JavaConverters.asScalaMapConverter(dataStoreParams).asScala()
                              .toMap(Predef.<Tuple2<String, String>>conforms());
        GeoMesaInputFormat$.MODULE$.configure(job, scalaParams, featureTypeName, Option.apply(filter));
    }
}