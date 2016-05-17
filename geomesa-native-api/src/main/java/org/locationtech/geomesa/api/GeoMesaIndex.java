package org.locationtech.geomesa.api;

import com.vividsolutions.jts.geom.Geometry;

import java.util.Date;

/**
 * GeoMesaIndex is an API for utilizing GeoMesa as a spatial index
 * without bringing in the Geotools SimpleFeature data model
 * @param <T>
 */
public interface GeoMesaIndex<T> {

    IndexType[] supportedIndexes();

    /**
     * Query a GeoMesa index
     * @param query
     * @return
     */
    Iterable<T> query(GeoMesaQuery query);

    /**
     * Insert a value in the GeoMesa index
     * @param value
     * @param geometry
     * @param dtg date time of the object or null if using spatial only
     * @return identifier of the object stored
     */
    String insert(T value, Geometry geometry, Date dtg);

    /**
     * Insert a value in the GeoMesa index
     * @param id identifier to use for the value
     * @param value
     * @param geometry
     * @param dtg date time of the object or null if using spatial only
     */
    void insert(String id, T value, Geometry geometry, Date dtg);

    /**
     * Update a given identifier with a new value
     * @param id
     * @param newValue
     * @param geometry
     * @param dtg
     */
    void update(String id, T newValue, Geometry geometry, Date dtg);

    /**
     * Delete a value from the GeoMesa index
     * @param id
     */
    void delete(String id);

}
