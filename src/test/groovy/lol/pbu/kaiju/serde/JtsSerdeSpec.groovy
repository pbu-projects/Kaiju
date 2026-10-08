package lol.pbu.kaiju.serde

import io.micronaut.json.JsonMapper
import io.micronaut.serde.exceptions.SerdeException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Specification

@MicronautTest
class JtsSerdeSpec extends Specification {

    @Inject
    JsonMapper jsonMapper

    @Inject
    JtsGeometrySerde geometrySerde

    @Inject
    JtsPointSerde pointSerde

    @Inject
    JtsPolygonSerde polygonSerde

    @Inject
    JtsMultiPolygonSerde multiPolygonSerde

    @Shared
    GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)

    private Point createTestPoint(double x = -105.0, double y = 39.0) {
        return geometryFactory.createPoint(new Coordinate(x, y))
    }

    private Polygon createTestPolygon() {
        Coordinate[] coords = [
                new Coordinate(-105.0, 39.0),
                new Coordinate(-104.0, 39.0),
                new Coordinate(-104.0, 40.0),
                new Coordinate(-105.0, 40.0),
                new Coordinate(-105.0, 39.0)
        ]
        return geometryFactory.createPolygon(coords)
    }

    private MultiPolygon createTestMultiPolygon() {
        return geometryFactory.createMultiPolygon([createTestPolygon()] as Polygon[])
    }

    /********** Geometry Serialization Tests **********/

    def "JtsGeometrySerde | should serialize Point to valid GeoJSON"() {
        given: "a test Point"
        Point point = createTestPoint(-104.9903, 39.7392)

        when: "serialized to JSON"
        String json = jsonMapper.writeValueAsString(point)

        then: "it produces expected GeoJSON structure"
        json.contains('"type":"Point"')
        json.contains('"coordinates":[-104.9903,39.7392]')
    }

    def "JtsGeometrySerde | should serialize Polygon to valid GeoJSON"() {
        given: "a test Polygon"
        Polygon polygon = createTestPolygon()

        when: "serialized to JSON"
        String json = jsonMapper.writeValueAsString(polygon)

        then: "it produces expected GeoJSON structure"
        json.contains('"type":"Polygon"')
        json.contains('"coordinates":[[[-105.0,39.0],[-104.0,39.0],[-104.0,40.0],[-105.0,40.0],[-105.0,39.0]]]')
    }

    def "JtsGeometrySerde | should serialize MultiPolygon to valid GeoJSON"() {
        given: "a test MultiPolygon"
        MultiPolygon multiPolygon = createTestMultiPolygon()

        when: "serialized to JSON"
        String json = jsonMapper.writeValueAsString(multiPolygon)

        then: "it produces expected GeoJSON structure"
        json.contains('"type":"MultiPolygon"')
        json.contains('"coordinates":[[[[')
    }

    def "JtsGeometrySerde | should fail fast when serializing unsupported geometry type"() {
        given: "an unsupported geometry type (LineString)"
        Coordinate[] coords = [
                new Coordinate(-105.0, 39.0),
                new Coordinate(-104.0, 40.0)
        ]
        LineString lineString = geometryFactory.createLineString(coords)

        when: "attempting to serialize"
        jsonMapper.writeValueAsString(lineString)

        then: "a SerdeException or IOException is thrown"
        def e = thrown(IOException)
        e.message.contains("Unsupported geometry type")
    }

    /********** Geometry Deserialization Tests **********/

    def "JtsGeometrySerde | should deserialize GeoJSON Point to JTS Point"() {
        given: "GeoJSON Point string"
        String json = '{"type":"Point","coordinates":[-104.9903,39.7392]}'

        when: "deserialized to Geometry"
        Geometry geom = jsonMapper.readValue(json, Geometry)

        then: "it is a Point with matching coordinates"
        geom instanceof Point
        geom.coordinate.x == -104.9903
        geom.coordinate.y == 39.7392
    }

    def "JtsGeometrySerde | should deserialize GeoJSON Polygon to JTS Polygon"() {
        given: "GeoJSON Polygon string"
        String json = '{"type":"Polygon","coordinates":[[[-105.0,39.0],[-104.0,39.0],[-104.0,40.0],[-105.0,40.0],[-105.0,39.0]]]}'

        when: "deserialized to Geometry"
        Geometry geom = jsonMapper.readValue(json, Geometry)

        then: "it is a Polygon with matching coordinates"
        geom instanceof Polygon
        geom.coordinates.length == 5
        geom.coordinates[0].x == -105.0
        geom.coordinates[0].y == 39.0
    }

    def "JtsGeometrySerde | should auto-close unclosed GeoJSON Polygon linear rings"() {
        given: "GeoJSON Polygon with unclosed ring (triangle of 3 coordinates without closed 4th point)"
        String json = '{"type":"Polygon","coordinates":[[[-105.0,39.0],[-104.0,39.0],[-104.0,40.0]]]}'

        when: "deserialized to Geometry"
        Geometry geom = jsonMapper.readValue(json, Geometry)

        then: "it is automatically closed into a valid 4-coordinate Polygon"
        geom instanceof Polygon
        geom.coordinates.length == 4
        geom.coordinates[0].equals2D(geom.coordinates[3])
    }

    def "JtsGeometrySerde | should deserialize GeoJSON MultiPolygon to JTS MultiPolygon"() {
        given: "GeoJSON MultiPolygon string"
        String json = '{"type":"MultiPolygon","coordinates":[[[[ -105.0, 39.0 ], [ -104.0, 39.0 ], [ -104.0, 40.0 ], [ -105.0, 40.0 ], [ -105.0, 39.0 ]]]]}'

        when: "deserialized to Geometry"
        Geometry geom = jsonMapper.readValue(json, Geometry)

        then: "it is a MultiPolygon"
        geom instanceof MultiPolygon
        geom.numGeometries == 1
    }

    def "JtsGeometrySerde | should reject unsupported geometry type on deserialization"() {
        given: "GeoJSON with unsupported type"
        String json = '{"type":"LineString","coordinates":[[-105.0,39.0],[-104.0,40.0]]}'

        when: "attempting to deserialize"
        jsonMapper.readValue(json, Geometry)

        then: "an exception is thrown"
        def e = thrown(IOException)
        e.message.contains("Unsupported geometry type")
    }

    /********** Specialized Typed Serde Tests **********/

    def "JtsPointSerde | should deserialize Point directly and reject non-Point"() {
        given: "Point JSON and Polygon JSON"
        String pointJson = '{"type":"Point","coordinates":[-105.0,39.0]}'
        String polyJson = '{"type":"Polygon","coordinates":[[[-105.0,39.0],[-104.0,39.0],[-104.0,40.0],[-105.0,40.0],[-105.0,39.0]]]}'

        when: "deserializing Point"
        Point pt = jsonMapper.readValue(pointJson, Point)

        then: "Point deserialization succeeds"
        pt != null
        pt.x == -105.0

        when: "deserializing Polygon into Point type"
        jsonMapper.readValue(polyJson, Point)

        then: "an exception is thrown"
        thrown(IOException)
    }

    def "JtsPolygonSerde | should deserialize Polygon directly and reject non-Polygon"() {
        given: "Point JSON and Polygon JSON"
        String pointJson = '{"type":"Point","coordinates":[-105.0,39.0]}'
        String polyJson = '{"type":"Polygon","coordinates":[[[-105.0,39.0],[-104.0,39.0],[-104.0,40.0],[-105.0,40.0],[-105.0,39.0]]]}'

        when: "deserializing Polygon"
        Polygon poly = jsonMapper.readValue(polyJson, Polygon)

        then: "Polygon deserialization succeeds"
        poly != null

        when: "deserializing Point into Polygon type"
        jsonMapper.readValue(pointJson, Polygon)

        then: "an exception is thrown"
        thrown(IOException)
    }

    def "JtsMultiPolygonSerde | should deserialize MultiPolygon directly and reject non-MultiPolygon"() {
        given: "Point JSON and MultiPolygon JSON"
        String pointJson = '{"type":"Point","coordinates":[-105.0,39.0]}'
        String multiPolyJson = '{"type":"MultiPolygon","coordinates":[[[[ -105.0, 39.0 ], [ -104.0, 39.0 ], [ -104.0, 40.0 ], [ -105.0, 40.0 ], [ -105.0, 39.0 ]]]]}'

        when: "deserializing MultiPolygon"
        MultiPolygon mp = jsonMapper.readValue(multiPolyJson, MultiPolygon)

        then: "MultiPolygon deserialization succeeds"
        mp != null

        when: "deserializing Point into MultiPolygon type"
        jsonMapper.readValue(pointJson, MultiPolygon)

        then: "an exception is thrown"
        thrown(IOException)
    }
}
