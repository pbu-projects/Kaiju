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

    /********** 3D Coordinates & Holes Tests **********/

    def "JtsGeometrySerde | should serialize and deserialize 3D Point with Z coordinate"() {
        given: "a 3D Point with Z coordinate"
        Point point3d = geometryFactory.createPoint(new Coordinate(-105.0, 39.0, 1600.0))

        when: "serialized to JSON"
        String json = jsonMapper.writeValueAsString(point3d)

        then: "Z coordinate is preserved in JSON"
        json.contains('"coordinates":[-105.0,39.0,1600.0]')

        when: "deserialized back to Geometry"
        Geometry deserialized = jsonMapper.readValue(json, Geometry)

        then: "Z coordinate is preserved on deserialized Point"
        deserialized instanceof Point
        deserialized.coordinate.x == -105.0
        deserialized.coordinate.y == 39.0
        deserialized.coordinate.z == 1600.0
    }

    def "JtsGeometrySerde | should serialize and deserialize Polygon with interior ring (hole)"() {
        given: "a Polygon with an exterior shell and an interior hole"
        Coordinate[] shellCoords = [
                new Coordinate(-105.0, 39.0),
                new Coordinate(-103.0, 39.0),
                new Coordinate(-103.0, 41.0),
                new Coordinate(-105.0, 41.0),
                new Coordinate(-105.0, 39.0)
        ]
        Coordinate[] holeCoords = [
                new Coordinate(-104.5, 39.5),
                new Coordinate(-103.5, 39.5),
                new Coordinate(-103.5, 40.5),
                new Coordinate(-104.5, 40.5),
                new Coordinate(-104.5, 39.5)
        ]
        Polygon polygonWithHole = geometryFactory.createPolygon(
                geometryFactory.createLinearRing(shellCoords),
                [geometryFactory.createLinearRing(holeCoords)] as org.locationtech.jts.geom.LinearRing[]
        )

        when: "serialized to JSON"
        String json = jsonMapper.writeValueAsString(polygonWithHole)

        then: "both shell and hole rings are serialized"
        json.contains('"type":"Polygon"')

        when: "deserialized back to Geometry"
        Geometry deserialized = jsonMapper.readValue(json, Geometry)

        then: "polygon retains its interior ring"
        deserialized instanceof Polygon
        Polygon resultPoly = (Polygon) deserialized
        resultPoly.numInteriorRing == 1
    }

    def "JtsGeometrySerde | should serialize and deserialize MultiPolygon with interior ring"() {
        given: "a MultiPolygon containing a polygon with a hole"
        Coordinate[] shellCoords = [
                new Coordinate(-105.0, 39.0),
                new Coordinate(-103.0, 39.0),
                new Coordinate(-103.0, 41.0),
                new Coordinate(-105.0, 41.0),
                new Coordinate(-105.0, 39.0)
        ]
        Coordinate[] holeCoords = [
                new Coordinate(-104.5, 39.5),
                new Coordinate(-103.5, 39.5),
                new Coordinate(-103.5, 40.5),
                new Coordinate(-104.5, 40.5),
                new Coordinate(-104.5, 39.5)
        ]
        Polygon polygonWithHole = geometryFactory.createPolygon(
                geometryFactory.createLinearRing(shellCoords),
                [geometryFactory.createLinearRing(holeCoords)] as org.locationtech.jts.geom.LinearRing[]
        )
        MultiPolygon multiPolygon = geometryFactory.createMultiPolygon([polygonWithHole] as Polygon[])

        when: "serialized and deserialized"
        String json = jsonMapper.writeValueAsString(multiPolygon)
        Geometry deserialized = jsonMapper.readValue(json, Geometry)

        then: "deserialized MultiPolygon has member polygon with 1 hole"
        deserialized instanceof MultiPolygon
        MultiPolygon resultMp = (MultiPolygon) deserialized
        resultMp.numGeometries == 1
        ((Polygon) resultMp.getGeometryN(0)).numInteriorRing == 1
    }

    /********** Null Handling & Direct Constructor Tests **********/

    def "JtsGeometrySerde | should handle null values gracefully"() {
        given: "mock encoder"
        io.micronaut.serde.Encoder encoder = Mock(io.micronaut.serde.Encoder)
        io.micronaut.serde.Serializer.EncoderContext context = Mock(io.micronaut.serde.Serializer.EncoderContext)

        when: "serializing null Geometry via serde"
        geometrySerde.serialize(encoder, context, io.micronaut.core.type.Argument.of(Geometry), null)

        then: "encodeNull is invoked"
        1 * encoder.encodeNull()

        when: "deserializing null JSON"
        Geometry nullGeom = jsonMapper.readValue("null", Geometry)
        Point nullPoint = jsonMapper.readValue("null", Point)
        Polygon nullPoly = jsonMapper.readValue("null", Polygon)
        MultiPolygon nullMultiPoly = jsonMapper.readValue("null", MultiPolygon)

        then: "nulls are returned without exception"
        nullGeom == null
        nullPoint == null
        nullPoly == null
        nullMultiPoly == null
    }

    def "Specialized serdes | should serialize nulls without error"() {
        given: "mock encoder"
        io.micronaut.serde.Encoder encoder = Mock(io.micronaut.serde.Encoder)
        io.micronaut.serde.Serializer.EncoderContext context = Mock(io.micronaut.serde.Serializer.EncoderContext)

        when: "serializing nulls"
        pointSerde.serialize(encoder, context, io.micronaut.core.type.Argument.of(Point), null)
        polygonSerde.serialize(encoder, context, io.micronaut.core.type.Argument.of(Polygon), null)
        multiPolygonSerde.serialize(encoder, context, io.micronaut.core.type.Argument.of(MultiPolygon), null)

        then: "encodeNull is called each time"
        3 * encoder.encodeNull()
    }

    def "Specialized serdes | should handle null return from delegate when decoding null node"() {
        given: "mock decoder returning null node"
        io.micronaut.serde.Decoder decoder = Mock(io.micronaut.serde.Decoder)
        io.micronaut.serde.Deserializer.DecoderContext context = Mock(io.micronaut.serde.Deserializer.DecoderContext)
        decoder.decodeNode() >> null

        expect:
        pointSerde.deserialize(decoder, context, io.micronaut.core.type.Argument.of(Point)) == null
        polygonSerde.deserialize(decoder, context, io.micronaut.core.type.Argument.of(Polygon)) == null
        multiPolygonSerde.deserialize(decoder, context, io.micronaut.core.type.Argument.of(MultiPolygon)) == null
    }

    def "JtsGeometrySerde | direct constructor and getter instantiate default factory"() {
        when: "using default constructor"
        JtsGeometrySerde directSerde = new JtsGeometrySerde()

        then: "geometryFactory is available"
        directSerde.geometryFactory != null
        directSerde.geometryFactory.SRID == 4326
    }

    /********** Error Validation Branches **********/

    def "JtsGeometrySerde | should throw exception on invalid GeoJSON payloads"() {
        when: "deserializing invalid JSON #desc"
        jsonMapper.readValue(invalidJson, Geometry)

        then: "an exception is thrown"
        thrown(IOException)

        where:
        desc                          | invalidJson
        "not a JSON object"           | "123"
        "missing type"                | '{"coordinates":[1,2]}'
        "non-string type"             | '{"type":123,"coordinates":[1,2]}'
        "missing coordinates"         | '{"type":"Point"}'
        "non-array coordinates"       | '{"type":"Point","coordinates":"invalid"}'
        "empty polygon coordinates"   | '{"type":"Polygon","coordinates":[]}'
        "non-array shell"             | '{"type":"Polygon","coordinates":["invalid"]}'
        "non-array hole"              | '{"type":"Polygon","coordinates":[[[-105,39],[-104,39],[-104,40],[-105,40],[-105,39]],"bad"]}'
        "non-array multipolygon"      | '{"type":"MultiPolygon","coordinates":["bad"]}'
        "linear ring null coordinate" | '{"type":"Polygon","coordinates":[[[-105,39],null,[-104,40],[-105,39]]]}'
        "coordinate too small"        | '{"type":"Point","coordinates":[1]}'
        "coordinate not numbers"      | '{"type":"Point","coordinates":["a","b"]}'
        "linear ring too short"       | '{"type":"Polygon","coordinates":[[[-105,39],[-104,39]]]}'
    }
}
