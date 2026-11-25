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
import spock.lang.Unroll

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

    def "JtsPointSerde | deserializes Point directly"() {
        given: "Point JSON"
        String pointJson = '{"type":"Point","coordinates":[-105.0,39.0]}'

        when: "deserializing Point"
        Point pt = jsonMapper.readValue(pointJson, Point)

        then: "Point deserialization succeeds"
        verifyAll(pt) {
            it != null
            x == -105.0
        }
    }

    def "JtsPointSerde | rejects non-Point JSON"() {
        given: "Polygon JSON"
        String polyJson = '{"type":"Polygon","coordinates":[[[-105.0,39.0],[-104.0,39.0],[-104.0,40.0],[-105.0,40.0],[-105.0,39.0]]]}'

        when: "deserializing Polygon into Point type"
        jsonMapper.readValue(polyJson, Point)

        then: "an exception is thrown"
        thrown(IOException)
    }

    def "JtsPolygonSerde | deserializes Polygon directly"() {
        given: "Polygon JSON"
        String polyJson = '{"type":"Polygon","coordinates":[[[-105.0,39.0],[-104.0,39.0],[-104.0,40.0],[-105.0,40.0],[-105.0,39.0]]]}'

        when: "deserializing Polygon"
        Polygon poly = jsonMapper.readValue(polyJson, Polygon)

        then: "Polygon deserialization succeeds"
        poly != null
    }

    def "JtsPolygonSerde | rejects non-Polygon JSON"() {
        given: "Point JSON"
        String pointJson = '{"type":"Point","coordinates":[-105.0,39.0]}'

        when: "deserializing Point into Polygon type"
        jsonMapper.readValue(pointJson, Polygon)

        then: "an exception is thrown"
        thrown(IOException)
    }

    def "JtsMultiPolygonSerde | deserializes MultiPolygon directly"() {
        given: "MultiPolygon JSON"
        String multiPolyJson = '{"type":"MultiPolygon","coordinates":[[[[ -105.0, 39.0 ], [ -104.0, 39.0 ], [ -104.0, 40.0 ], [ -105.0, 40.0 ], [ -105.0, 39.0 ]]]]}'

        when: "deserializing MultiPolygon"
        MultiPolygon mp = jsonMapper.readValue(multiPolyJson, MultiPolygon)

        then: "MultiPolygon deserialization succeeds"
        mp != null
    }

    def "JtsMultiPolygonSerde | rejects non-MultiPolygon JSON"() {
        given: "Point JSON"
        String pointJson = '{"type":"Point","coordinates":[-105.0,39.0]}'

        when: "deserializing Point into MultiPolygon type"
        jsonMapper.readValue(pointJson, MultiPolygon)

        then: "an exception is thrown"
        thrown(IOException)
    }

    /********** 3D Coordinates & Holes Tests **********/

    private Polygon createPolygonWithHole() {
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
        return geometryFactory.createPolygon(
                geometryFactory.createLinearRing(shellCoords),
                [geometryFactory.createLinearRing(holeCoords)] as LinearRing[]
        )
    }

    def "JtsGeometrySerde | should serialize 3D Point with Z coordinate in JSON"() {
        given: "a 3D Point with Z coordinate"
        Point point3d = geometryFactory.createPoint(new Coordinate(-105.0, 39.0, 1600.0))

        when: "serialized to JSON"
        String json = jsonMapper.writeValueAsString(point3d)

        then: "Z coordinate is preserved in JSON"
        json.contains('"coordinates":[-105.0,39.0,1600.0]')
    }

    def "JtsGeometrySerde | should serialize and deserialize 3D Point with Z coordinate"() {
        given: "a 3D Point with Z coordinate"
        Point point3d = geometryFactory.createPoint(new Coordinate(-105.0, 39.0, 1600.0))

        when: "serialized and deserialized"
        String json = jsonMapper.writeValueAsString(point3d)
        Geometry deserialized = jsonMapper.readValue(json, Geometry)

        then: "Z coordinate is preserved on deserialized Point"
        verifyAll {
            deserialized instanceof Point
            deserialized.coordinate.x == -105.0
            deserialized.coordinate.y == 39.0
            deserialized.coordinate.z == 1600.0
        }
    }

    def "JtsGeometrySerde | should serialize Polygon with interior ring (hole) in JSON"() {
        given: "a Polygon with an exterior shell and an interior hole"
        Polygon polygonWithHole = createPolygonWithHole()

        when: "serialized to JSON"
        String json = jsonMapper.writeValueAsString(polygonWithHole)

        then: "both shell and hole rings are serialized"
        JsonNode node = jsonMapper.readValue(json, JsonNode)
        verifyAll {
            node.get("type").stringValue == "Polygon"
            node.get("coordinates").size() == 2
        }
    }

    def "JtsGeometrySerde | should serialize and deserialize Polygon with interior ring (hole)"() {
        given: "a Polygon with an exterior shell and an interior hole"
        Polygon polygonWithHole = createPolygonWithHole()

        when: "serialized and deserialized"
        String json = jsonMapper.writeValueAsString(polygonWithHole)
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

    def "JtsGeometrySerde | serializes null Geometry as JSON null"() {
        given: "mock encoder"
        Encoder encoder = Mock(Encoder)
        Serializer.EncoderContext context = Mock(Serializer.EncoderContext)

        when: "serializing null Geometry via serde"
        geometrySerde.serialize(encoder, context, Argument.of(Geometry), null)

        then: "encodeNull is invoked"
        1 * encoder.encodeNull()
    }

    @Unroll
    def "JtsGeometrySerde | deserializes null JSON to null #targetType.simpleName"() {
        when: "deserializing null JSON"
        def result = jsonMapper.readValue("null", targetType)

        then: "null is returned without exception"
        result == null

        where:
        targetType << [Geometry, Point, Polygon, MultiPolygon]
    }

    @Unroll
    def "Specialized serdes | #serdeName serializes null without error"() {
        given: "mock encoder"
        Encoder encoder = Mock(Encoder)
        Serializer.EncoderContext context = Mock(Serializer.EncoderContext)
        def serde = serdeFor(serdeName)

        when: "serializing null"
        serde.serialize(encoder, context, Argument.of(targetType), null)

        then: "encodeNull is called"
        1 * encoder.encodeNull()

        where:
        serdeName              | targetType
        "JtsPointSerde"        | Point
        "JtsPolygonSerde"      | Polygon
        "JtsMultiPolygonSerde" | MultiPolygon
    }

    @Unroll
    def "Specialized serdes | #serdeName returns null when decoding null node"() {
        given: "mock decoder returning null node"
        Decoder decoder = Mock(Decoder)
        Deserializer.DecoderContext context = Mock(Deserializer.DecoderContext)
        decoder.decodeNode() >> null
        def serde = serdeFor(serdeName)

        expect: "null is returned"
        serde.deserialize(decoder, context, Argument.of(targetType)) == null

        where:
        serdeName              | targetType
        "JtsPointSerde"        | Point
        "JtsPolygonSerde"      | Polygon
        "JtsMultiPolygonSerde" | MultiPolygon
    }

    private Object serdeFor(String serdeName) {
        switch (serdeName) {
            case "JtsPointSerde":
                return pointSerde
            case "JtsPolygonSerde":
                return polygonSerde
            case "JtsMultiPolygonSerde":
                return multiPolygonSerde
            default:
                throw new IllegalArgumentException("Unknown serde: ${serdeName}")
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

    @Unroll
    def "JtsGeometrySerde | should throw exception on invalid GeoJSON payloads (#desc)"() {
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

    def "JtsGeometrySerde | falls back to SRID 4326 factory when given null"() {
        when: "creating serde with a null GeometryFactory"
        JtsGeometrySerde fallbackSerde = new JtsGeometrySerde(null)

        then: "default SRID 4326 factory is created"
        verifyAll(fallbackSerde.geometryFactory) {
            it != null
            SRID == 4326
        }
    }

    @Unroll
    def "JtsGeometrySerde | deserializeFromNode returns null for #caseName"() {
        when: "deserializing #caseName"
        Geometry result = geometrySerde.deserializeFromNode(node)

        then: "null is returned"
        result == null

        where:
        caseName       | node
        "null node"    | null
        "JsonNode null"| JsonNode.nullNode()
    }

    def "JtsGeometrySerde | ignores null Z elevation on Point coordinates"() {
        given: "a Point node with null Z coordinate"
        JsonNode nullZPoint = jsonMapper.readValue('{"type":"Point","coordinates":[-105.0,39.0,null]}', JsonNode)

        when: "deserializing the point"
        Geometry pointResult = geometrySerde.deserializeFromNode(nullZPoint)

        then: "a Point is returned with NaN Z elevation"
        verifyAll {
            pointResult instanceof Point
            Double.isNaN(pointResult.coordinate.z)
        }
    }

    private static List<JsonNode> createCircleRingNodes(int count, double radius) {
        (0..<count).collect { int i ->
            double angle = (2.0d * Math.PI * i) / (count as double)
            JsonNode.createArrayNode([
                    JsonNode.createNumberNode(-105.0d + Math.cos(angle) * radius),
                    JsonNode.createNumberNode(39.0d + Math.sin(angle) * radius)
            ])
        }
    }

    private static JsonNode createOversizedArrayGeometry(String geometryType) {
        List<JsonNode> items = (0..10_000).collect { JsonNode.createArrayNode([]) }
        JsonNode.createObjectNode([
                type       : JsonNode.createStringNode(geometryType),
                coordinates: JsonNode.createArrayNode(items)
        ])
    }

    private static JsonNode createTotalVerticesExceededPolygon() {
        JsonNode.createObjectNode([
                type       : JsonNode.createStringNode("Polygon"),
                coordinates: JsonNode.createArrayNode([
                        JsonNode.createArrayNode(createCircleRingNodes(6_000, 0.2d)),
                        JsonNode.createArrayNode(createCircleRingNodes(4_001, 0.05d))
                ])
        ])
    }

    @Unroll
    def "JtsGeometrySerde | rejects #caseName exceeding 10000 limit"() {
        when: "deserializing oversized geometry"
        geometrySerde.deserializeFromNode(oversizedNode)

        then: "SerdeException mentions the 10000 limit"
        SerdeException ex = thrown()
        ex.message.contains("10000")

        where:
        caseName                              | oversizedNode
        "polygon with over 10000 rings"       | createOversizedArrayGeometry("Polygon")
        "multipolygon with over 10000 parts"  | createOversizedArrayGeometry("MultiPolygon")
        "polygon over 10000 total vertices"   | createTotalVerticesExceededPolygon()
    }
}
