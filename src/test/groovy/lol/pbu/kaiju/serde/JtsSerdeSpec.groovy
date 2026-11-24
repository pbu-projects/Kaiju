package lol.pbu.kaiju.serde

import io.micronaut.core.type.Argument
import io.micronaut.json.JsonMapper
import io.micronaut.json.tree.JsonNode
import io.micronaut.serde.Decoder
import io.micronaut.serde.Deserializer
import io.micronaut.serde.Encoder
import io.micronaut.serde.Serializer
import io.micronaut.serde.exceptions.SerdeException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.LinearRing
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
        verifyAll {
            json.contains('"type":"Point"')
            json.contains('"coordinates":[-104.9903,39.7392]')
        }
    }

    def "JtsGeometrySerde | should serialize Polygon to valid GeoJSON"() {
        given: "a test Polygon"
        Polygon polygon = createTestPolygon()

        when: "serialized to JSON"
        String json = jsonMapper.writeValueAsString(polygon)

        then: "it produces expected GeoJSON structure"
        verifyAll {
            json.contains('"type":"Polygon"')
            json.contains('"coordinates":[[[-105.0,39.0],[-104.0,39.0],[-104.0,40.0],[-105.0,40.0],[-105.0,39.0]]]')
        }
    }

    def "JtsGeometrySerde | should serialize MultiPolygon to valid GeoJSON"() {
        given: "a test MultiPolygon"
        MultiPolygon multiPolygon = createTestMultiPolygon()

        when: "serialized to JSON"
        String json = jsonMapper.writeValueAsString(multiPolygon)

        then: "it produces expected GeoJSON structure"
        verifyAll {
            json.contains('"type":"MultiPolygon"')
            json.contains('"coordinates":[[[[')
        }
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
        verifyAll {
            geom instanceof Point
            geom.coordinate.x == -104.9903
            geom.coordinate.y == 39.7392
        }
    }

    def "JtsGeometrySerde | should deserialize GeoJSON Polygon to JTS Polygon"() {
        given: "GeoJSON Polygon string"
        String json = '{"type":"Polygon","coordinates":[[[-105.0,39.0],[-104.0,39.0],[-104.0,40.0],[-105.0,40.0],[-105.0,39.0]]]}'

        when: "deserialized to Geometry"
        Geometry geom = jsonMapper.readValue(json, Geometry)

        then: "it is a Polygon with matching coordinates"
        verifyAll {
            geom instanceof Polygon
            geom.coordinates.length == 5
            geom.coordinates[0].x == -105.0
            geom.coordinates[0].y == 39.0
        }
    }

    def "JtsGeometrySerde | should auto-close unclosed GeoJSON Polygon linear rings"() {
        given: "GeoJSON Polygon with unclosed ring (triangle of 3 coordinates without closed 4th point)"
        String json = '{"type":"Polygon","coordinates":[[[-105.0,39.0],[-104.0,39.0],[-104.0,40.0]]]}'

        when: "deserialized to Geometry"
        Geometry geom = jsonMapper.readValue(json, Geometry)

        then: "it is automatically closed into a valid 4-coordinate Polygon"
        verifyAll {
            geom instanceof Polygon
            geom.coordinates.length == 4
            geom.coordinates[0].equals2D(geom.coordinates[3])
        }
    }

    def "JtsGeometrySerde | should deserialize GeoJSON MultiPolygon to JTS MultiPolygon"() {
        given: "GeoJSON MultiPolygon string"
        String json = '{"type":"MultiPolygon","coordinates":[[[[ -105.0, 39.0 ], [ -104.0, 39.0 ], [ -104.0, 40.0 ], [ -105.0, 40.0 ], [ -105.0, 39.0 ]]]]}'

        when: "deserialized to Geometry"
        Geometry geom = jsonMapper.readValue(json, Geometry)

        then: "it is a MultiPolygon"
        verifyAll {
            geom instanceof MultiPolygon
            geom.numGeometries == 1
        }
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
        verifyAll(pt) {
            it != null
            x == -105.0
        }

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
        verifyAll {
            deserialized instanceof Point
            deserialized.coordinate.x == -105.0
            deserialized.coordinate.y == 39.0
            deserialized.coordinate.z == 1600.0
        }
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
                [geometryFactory.createLinearRing(holeCoords)] as LinearRing[]
        )

        when: "serialized to JSON"
        String json = jsonMapper.writeValueAsString(polygonWithHole)

        then: "both shell and hole rings are serialized"
        json.contains('"type":"Polygon"')

        when: "deserialized back to Geometry"
        Geometry deserialized = jsonMapper.readValue(json, Geometry)

        then: "polygon retains its interior ring"
        verifyAll {
            deserialized instanceof Polygon
            ((Polygon) deserialized).numInteriorRing == 1
        }
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
                [geometryFactory.createLinearRing(holeCoords)] as LinearRing[]
        )
        MultiPolygon multiPolygon = geometryFactory.createMultiPolygon([polygonWithHole] as Polygon[])

        when: "serialized and deserialized"
        String json = jsonMapper.writeValueAsString(multiPolygon)
        Geometry deserialized = jsonMapper.readValue(json, Geometry)

        then: "deserialized MultiPolygon has member polygon with 1 hole"
        verifyAll {
            deserialized instanceof MultiPolygon
            ((MultiPolygon) deserialized).numGeometries == 1
            ((Polygon) ((MultiPolygon) deserialized).getGeometryN(0)).numInteriorRing == 1
        }
    }

    /********** Null Handling & Direct Constructor Tests **********/

    def "JtsGeometrySerde | should handle null values gracefully"() {
        given: "mock encoder"
        Encoder encoder = Mock(Encoder)
        Serializer.EncoderContext context = Mock(Serializer.EncoderContext)

        when: "serializing null Geometry via serde"
        geometrySerde.serialize(encoder, context, Argument.of(Geometry), null)

        then: "encodeNull is invoked"
        1 * encoder.encodeNull()

        when: "deserializing null JSON"
        Geometry nullGeom = jsonMapper.readValue("null", Geometry)
        Point nullPoint = jsonMapper.readValue("null", Point)
        Polygon nullPoly = jsonMapper.readValue("null", Polygon)
        MultiPolygon nullMultiPoly = jsonMapper.readValue("null", MultiPolygon)

        then: "nulls are returned without exception"
        verifyAll {
            nullGeom == null
            nullPoint == null
            nullPoly == null
            nullMultiPoly == null
        }
    }

    def "Specialized serdes | should serialize nulls without error"() {
        given: "mock encoder"
        Encoder encoder = Mock(Encoder)
        Serializer.EncoderContext context = Mock(Serializer.EncoderContext)

        when: "serializing nulls"
        pointSerde.serialize(encoder, context, Argument.of(Point), null)
        polygonSerde.serialize(encoder, context, Argument.of(Polygon), null)
        multiPolygonSerde.serialize(encoder, context, Argument.of(MultiPolygon), null)

        then: "encodeNull is called each time"
        3 * encoder.encodeNull()
    }

    def "Specialized serdes | should handle null return from delegate when decoding null node"() {
        given: "mock decoder returning null node"
        Decoder decoder = Mock(Decoder)
        Deserializer.DecoderContext context = Mock(Deserializer.DecoderContext)
        decoder.decodeNode() >> null

        expect:
        verifyAll {
            pointSerde.deserialize(decoder, context, Argument.of(Point)) == null
            polygonSerde.deserialize(decoder, context, Argument.of(Polygon)) == null
            multiPolygonSerde.deserialize(decoder, context, Argument.of(MultiPolygon)) == null
        }
    }

    def "JtsGeometrySerde | direct constructor and getter instantiate default factory"() {
        when: "using default constructor"
        JtsGeometrySerde directSerde = new JtsGeometrySerde()

        then: "geometryFactory is available"
        verifyAll(directSerde.geometryFactory) {
            it != null
            SRID == 4326
        }
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
        "empty polygon ring"          | '{"type":"Polygon","coordinates":[[]]}'
        "non-array shell"             | '{"type":"Polygon","coordinates":["invalid"]}'
        "non-array hole"              | '{"type":"Polygon","coordinates":[[[-105,39],[-104,39],[-104,40],[-105,40],[-105,39]],"bad"]}'
        "null hole"                   | '{"type":"Polygon","coordinates":[[[-105,39],[-104,39],[-104,40],[-105,40],[-105,39]],null]}'
        "non-array multipolygon"      | '{"type":"MultiPolygon","coordinates":["bad"]}'
        "null multipolygon member"    | '{"type":"MultiPolygon","coordinates":[null]}'
        "linear ring null coordinate" | '{"type":"Polygon","coordinates":[[[-105,39],null,[-104,40],[-105,39]]]}'
        "coordinate not an array"     | '{"type":"Polygon","coordinates":[[[-105,39],"bad",[-104,40],[-105,39]]]}'
        "coordinate too small"        | '{"type":"Point","coordinates":[1]}'
        "coordinate not numbers"      | '{"type":"Point","coordinates":["a","b"]}'
        "linear ring too short"       | '{"type":"Polygon","coordinates":[[[-105,39],[-104,39]]]}'
    }

    def "JtsGeometrySerde | handles null GeometryFactory, null JsonNode, and null elevation"() {
        given: "a serde created with null GeometryFactory"
        JtsGeometrySerde fallbackSerde = new JtsGeometrySerde(null)
        JsonNode nullZPoint = jsonMapper.readValue('{"type":"Point","coordinates":[-105.0,39.0,null]}', JsonNode)

        when: "deserializing null node, JsonNode.nullNode(), and point with null elevation"
        Geometry nullResult = fallbackSerde.deserializeFromNode(null)
        Geometry jsonNullResult = fallbackSerde.deserializeFromNode(JsonNode.nullNode())
        Geometry pointResult = fallbackSerde.deserializeFromNode(nullZPoint)

        then: "default factory is initialized, nulls return null, and null Z is ignored"
        verifyAll {
            fallbackSerde.geometryFactory.SRID == 4326
            nullResult == null
            jsonNullResult == null
            pointResult instanceof Point
            Double.isNaN(pointResult.coordinate.z)
        }
    }

    def "JtsGeometrySerde | rejects polygons and multipolygons exceeding ring, member, or total vertex limits"() {
        given: "an oversized polygon coordinates list, an oversized multipolygon list, and a two-ring polygon exceeding MAX_VERTICES"
        List<JsonNode> tooManyRings = (0..10_000).collect { JsonNode.createArrayNode([]) }
        JsonNode tooManyRingsPolygon = JsonNode.createObjectNode([
                type       : JsonNode.createStringNode("Polygon"),
                coordinates: JsonNode.createArrayNode(tooManyRings)
        ])
        JsonNode tooManyPolysMulti = JsonNode.createObjectNode([
                type       : JsonNode.createStringNode("MultiPolygon"),
                coordinates: JsonNode.createArrayNode(tooManyRings)
        ])

        List<JsonNode> shellRing = (0..<6_000).collect { int i ->
            double angle = (2.0d * Math.PI * i) / 6_000.0d
            JsonNode.createArrayNode([
                    JsonNode.createNumberNode(-105.0d + Math.cos(angle) * 0.2d),
                    JsonNode.createNumberNode(39.0d + Math.sin(angle) * 0.2d)
            ])
        }
        List<JsonNode> holeRing = (0..<4_001).collect { int i ->
            double angle = (2.0d * Math.PI * i) / 4_001.0d
            JsonNode.createArrayNode([
                    JsonNode.createNumberNode(-105.0d + Math.cos(angle) * 0.05d),
                    JsonNode.createNumberNode(39.0d + Math.sin(angle) * 0.05d)
            ])
        }
        JsonNode totalVerticesExceeded = JsonNode.createObjectNode([
                type       : JsonNode.createStringNode("Polygon"),
                coordinates: JsonNode.createArrayNode([
                        JsonNode.createArrayNode(shellRing),
                        JsonNode.createArrayNode(holeRing)
                ])
        ])

        when: "deserializing polygon with more than 10000 rings"
        geometrySerde.deserializeFromNode(tooManyRingsPolygon)

        then:
        def polyEx = thrown(SerdeException)
        polyEx.message.contains("10000")

        when: "deserializing multipolygon with more than 10000 members"
        geometrySerde.deserializeFromNode(tooManyPolysMulti)

        then:
        def multiEx = thrown(SerdeException)
        multiEx.message.contains("10000")

        when: "deserializing multi-ring polygon whose cumulative vertices exceed 10000"
        geometrySerde.deserializeFromNode(totalVerticesExceeded)

        then:
        def totalEx = thrown(SerdeException)
        totalEx.message.contains("10000")
    }
}
