/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.api;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.gson.Gson;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.security.Authorizations;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nullable;
import java.time.ZonedDateTime;
import java.util.Date;

public class GeoMesaIndexTest {

    public static class DomainObject {
        public final String id;
        public final int intValue;
        public final double doubleValue;

        public DomainObject(String id, int intValue, double doubleValue) {
            this.id = id;
            this.intValue = intValue;
            this.doubleValue = doubleValue;
        }
    }

    public static class DomainObjectValueSerializer implements ValueSerializer<DomainObject> {
        public static final Gson gson = new Gson();
        @Override
        public byte[] toBytes(DomainObject o) {
            return gson.toJson(o).getBytes();
        }

        @Override
        public DomainObject fromBytes(byte[] bytes) {
            return gson.fromJson(new String(bytes), DomainObject.class);
        }
    }

    final DomainObject one = new DomainObject("1", 1, 1.0);
    final DomainObject two = new DomainObject("2", 2, 2.0);
    final GeometryFactory gf = JTSFactoryFinder.getGeometryFactory();

    @Test
    public void testNativeAPI() {


        final GeoMesaIndex<DomainObject> index =
                AccumuloGeoMesaIndex.build("hello", "zoo1:2181", "mycloud", "myuser", "mypass",
                        true, new DomainObjectValueSerializer());

        index.insert(
                one.id,
                one,
                gf.createPoint(new Coordinate(-78.0, 38.0)),
                date("2016-01-01T12:15:00.000Z"));
        index.insert(
                two.id,
                two,
                gf.createPoint(new Coordinate(-78.0, 40.0)),
                date("2016-02-01T12:15:00.000Z"));

        final GeoMesaQuery q =
                GeoMesaQuery.GeoMesaQueryBuilder.builder()
                        .within(-79.0, 37.0, -77.0, 39.0)
                        .during(date("2016-01-01T00:00:00.000Z"), date("2016-03-01T00:00:00.000Z"))
                        .build();
        final Iterable<DomainObject> results = index.query(q);

        final Iterable<String> ids = Iterables.transform(results, new Function<DomainObject, String>() {
            @Nullable
            @Override
            public String apply(@Nullable DomainObject domainObject) {
                return domainObject.id;
            }
        });
        Assert.assertArrayEquals("Invalid results", new String[] { "1" }, Iterables.toArray(ids, String.class));

        index.delete("1");

        final Iterable<DomainObject> resultsAfterDelete = index.query(q);
        final Iterable<String> idsPostDelete = Iterables.transform(resultsAfterDelete, new Function<DomainObject, String>() {
            @Nullable
            @Override
            public String apply(@Nullable DomainObject domainObject) {
                System.out.println("Got id " + domainObject.id);
                return domainObject.id;
            }
        });
        Assert.assertArrayEquals("Invalid results", new String[] { }, Iterables.toArray(idsPostDelete, String.class));
    }

    @Test
    public void testVisibilityNativeAPI() throws Exception {
        String instanceId = "testVis";

        MockInstance mockInstance = new MockInstance(instanceId);
        Connector conn = mockInstance.getConnector("myuser", new PasswordToken("password".getBytes()));
        conn.securityOperations().changeUserAuthorizations("myuser", new Authorizations("user", "admin"));
        conn.securityOperations().createLocalUser("nonpriv", new PasswordToken("nonpriv".getBytes("UTF8")));
        conn.securityOperations().changeUserAuthorizations("nonpriv", new Authorizations("user"));

        final GeoMesaIndex<DomainObject> index =
                AccumuloGeoMesaIndex.build("securityTest", "zoo1:2181", instanceId, "myuser", "password",
                        true, new DomainObjectValueSerializer());

        index.insert(
                one.id,
                one,
                gf.createPoint(new Coordinate(-78.0, 38.0)),
                date("2016-01-01T12:15:00.000Z"),
                "user");
        index.insert(
                two.id,
                two,
                gf.createPoint(new Coordinate(-78.0, 40.0)),
                date("2016-02-01T12:15:00.000Z"),
                "admin");

        final Iterable<DomainObject> results = index.query(GeoMesaQuery.include());
        Assert.assertEquals(Iterables.size(results), 2);

        // Query again at the lower level.
        final GeoMesaIndex<DomainObject> index2 =
                AccumuloGeoMesaIndex.build("securityTest", "zoo1:2181", instanceId, "nonpriv", "nonpriv",
                        true, new DomainObjectValueSerializer());

        final Iterable<DomainObject> results2 = index2.query(GeoMesaQuery.include());
        Assert.assertEquals(Iterables.size(results2), 1);
    }

    private Date date(String s) {
        return Date.from(ZonedDateTime.parse(s).toInstant());
    }
}
