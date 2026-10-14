package lol.pbu.kaiju.service

import spock.lang.Specification
import spock.lang.Unroll

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class TigerwebContractValidatorSpec extends Specification {

    TigerwebContractValidator validator = new TigerwebContractValidator()

    def "SUCCESS | should validate authoritative Denver County fixture successfully"() {
        given: "authoritative Denver fixture stream"
        InputStream fixtureStream = getClass().getResourceAsStream("/fixtures/denver-county-tigerweb.json")
        assert fixtureStream != null
        byte[] payload = fixtureStream.readAllBytes()

        when: "validating the GeoJSON payload"
        validator.validateResponseSchema(payload)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "SUCCESS | should validate valid Polygon response"() {
        given: "valid GeoJSON with closed Polygon and mandatory census properties"
        String validPolygon = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [-105.1, 39.7],
                            [-104.9, 39.7],
                            [-104.9, 39.8],
                            [-105.1, 39.8],
                            [-105.1, 39.7]
                        ]]
                    },
                    "properties": {
                        "BASENAME": "Denver",
                        "GEOID": "08031",
                        "STATE": "08"
                    }
                }
            ]
        }'''

        when:
        validator.validateResponseSchema(validPolygon)

        then:
        noExceptionThrown()
    }

    def "SUCCESS | should validate valid HttpRequest contract"() {
        given: "a compliant Census TIGERweb query request"
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"))
                .header("Accept", "application/json, application/geo+json")
                .header("User-Agent", "Kaiju-GIS-Ingestion/1.0")
                .GET()
                .build()

        when:
        validator.validateRequest(request)

        then:
        noExceptionThrown()
    }

    def "SUCCESS | should re-wrap InputStream response so that it remains readable"() {
        given: "a response wrapping an InputStream"
        String validPolygon = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [-105.1, 39.7],
                            [-104.9, 39.7],
                            [-104.9, 39.8],
                            [-105.1, 39.8],
                            [-105.1, 39.7]
                        ]]
                    },
                    "properties": {
                        "BASENAME": "Denver",
                        "GEOID": "08031",
                        "STATE": "08"
                    }
                }
            ]
        }'''
        InputStream originalStream = new ByteArrayInputStream(validPolygon.getBytes("UTF-8"))
        HttpResponse<InputStream> stubResponse = new StubHttpResponse<InputStream>(200, originalStream)

        when: "validating the response"
        HttpResponse<InputStream> validatedResponse = validator.validateResponse(stubResponse)

        then: "the returned response contains a readable InputStream"
        validatedResponse != null
        validatedResponse.statusCode() == 200
        validatedResponse.body() != null
        String readBack = new String(validatedResponse.body().readAllBytes(), "UTF-8")
        readBack.contains("FeatureCollection")
    }

    def "REJECT | should throw ContractValidationException when HttpRequest is null"() {
        when:
        validator.validateRequest(null)

        then:
        def e = thrown(ContractValidationException)
        e.message.contains("HttpRequest must not be null")
    }

    def "REJECT | should reject non-GET request method"() {
        given: "a POST request"
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?f=geojson"))
                .header("Accept", "application/geo+json")
                .header("User-Agent", "Kaiju-GIS-Ingestion/1.0")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()

        when:
        validator.validateRequest(request)

        then:
        def e = thrown(ContractValidationException)
        e.message.contains("Expected HTTP GET method")
    }

    def "REJECT | should reject insecure HTTP scheme in request"() {
        given: "an unencrypted HTTP request"
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?f=geojson"))
                .header("Accept", "application/geo+json")
                .header("User-Agent", "Kaiju-GIS-Ingestion/1.0")
                .GET()
                .build()

        when:
        validator.validateRequest(request)

        then:
        def e = thrown(ContractValidationException)
        e.message.contains("Expected HTTPS scheme")
    }

    def "REJECT | should reject non-census host"() {
        given: "a request targeting an external unauthorized host"
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://malicious.example.com/arcgis/rest/services/TIGERweb/query?f=geojson"))
                .header("Accept", "application/geo+json")
                .header("User-Agent", "Kaiju-GIS-Ingestion/1.0")
                .GET()
                .build()

        when:
        validator.validateRequest(request)

        then:
        def e = thrown(ContractValidationException)
        e.message.contains("Expected census.gov host")
    }

    def "REJECT | should reject invalid path prefix"() {
        given: "a request with wrong path prefix"
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://tigerweb.geo.census.gov/other/service/query?f=geojson"))
                .header("Accept", "application/geo+json")
                .header("User-Agent", "Kaiju-GIS-Ingestion/1.0")
                .GET()
                .build()

        when:
        validator.validateRequest(request)

        then:
        def e = thrown(ContractValidationException)
        e.message.contains("Expected path starting with /arcgis/rest/services/TIGERweb/")
    }

    def "REJECT | should reject missing f=geojson or f=json query parameter"() {
        given: "a request missing format parameter"
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?where=BASENAME%3D%27Denver%27"))
                .header("Accept", "application/geo+json")
                .header("User-Agent", "Kaiju-GIS-Ingestion/1.0")
                .GET()
                .build()

        when:
        validator.validateRequest(request)

        then:
        def e = thrown(ContractValidationException)
        e.message.contains("must include query parameter f=geojson or f=json")
    }

    def "REJECT | should reject missing Accept header"() {
        given: "a request without JSON accept header"
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?f=geojson"))
                .header("Accept", "text/html")
                .header("User-Agent", "Kaiju-GIS-Ingestion/1.0")
                .GET()
                .build()

        when:
        validator.validateRequest(request)

        then:
        def e = thrown(ContractValidationException)
        e.message.contains("Accept header must include application/json or application/geo+json")
    }

    def "REJECT | should reject missing User-Agent header"() {
        given: "a request without User-Agent header"
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?f=geojson"))
                .header("Accept", "application/geo+json")
                .GET()
                .build()

        when:
        validator.validateRequest(request)

        then:
        def e = thrown(ContractValidationException)
        e.message.contains("User-Agent header must be provided")
    }

    def "REJECT | should reject null response in validateResponse"() {
        when:
        validator.validateResponse(null)

        then:
        def e = thrown(ContractValidationException)
        e.message.contains("HttpResponse must not be null")
    }

    def "REJECT | should reject null response body for 200 response"() {
        given:
        HttpResponse stubResponse = new StubHttpResponse<Object>(200, null)

        when:
        validator.validateResponse(stubResponse)

        then:
        def e = thrown(SchemaDriftException)
        e.message.contains("Response body must not be null")
    }

    def "REJECT | should reject empty or non-JSON payload"() {
        when:
        validator.validateResponseSchema("")

        then:
        def e1 = thrown(SchemaDriftException)
        e1.message.contains("must not be empty")

        when:
        validator.validateResponseSchema("this is not json at all")

        then:
        def e2 = thrown(SchemaDriftException)
        e2.message.contains("not valid JSON")
    }

    def "REJECT | should reject non-FeatureCollection root"() {
        when:
        validator.validateResponseSchema('{"type": "Polygon", "coordinates": []}')

        then:
        def e = thrown(SchemaDriftException)
        e.message.contains("Expected GeoJSON type 'FeatureCollection'")
    }

    def "REJECT | should reject empty features array"() {
        when:
        validator.validateResponseSchema('{"type": "FeatureCollection", "features": []}')

        then:
        def e = thrown(SchemaDriftException)
        e.message.contains("contains no features")
    }

    def "REJECT | should reject non-Feature item in features array"() {
        when:
        validator.validateResponseSchema('{"type": "FeatureCollection", "features": [{"type": "Invalid"}]}')

        then:
        def e = thrown(SchemaDriftException)
        e.message.contains("Expected feature type 'Feature'")
    }

    def "REJECT | should reject missing geometry object"() {
        when:
        validator.validateResponseSchema('{"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {"BASENAME": "Denver", "GEOID": "08031", "STATE": "08"}}]}')

        then:
        def e = thrown(SchemaDriftException)
        e.message.contains("Feature must contain a non-null geometry object")
    }

    @Unroll
    def "REJECT | should reject unapproved geometry type: #badType"(String badType) {
        given:
        String payload = """{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "${badType}",
                        "coordinates": [10.0, 20.0]
                    },
                    "properties": {
                        "BASENAME": "Denver",
                        "GEOID": "08031",
                        "STATE": "08"
                    }
                }
            ]
        }"""

        when:
        validator.validateResponseSchema(payload)

        then:
        def e = thrown(SchemaDriftException)
        e.message.contains("Authoritative boundary geometry type must be Polygon or MultiPolygon")

        where:
        badType << ["Point", "LineString", "MultiPoint", "GeometryCollection"]
    }

    def "REJECT | should reject unclosed linear ring in Polygon"() {
        given: "a Polygon ring where first and last points do not match"
        String unclosedPolygon = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [-105.1, 39.7],
                            [-104.9, 39.7],
                            [-104.9, 39.8],
                            [-105.1, 39.8]
                        ]]
                    },
                    "properties": {
                        "BASENAME": "Denver",
                        "GEOID": "08031",
                        "STATE": "08"
                    }
                }
            ]
        }'''

        when:
        validator.validateResponseSchema(unclosedPolygon)

        then:
        def e = thrown(SchemaDriftException)
        e.message.contains("Linear ring must be closed")
    }

    def "REJECT | should reject ring with fewer than 4 coordinates"() {
        given: "a Polygon ring with only 3 coordinates"
        String shortRingPolygon = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [-105.1, 39.7],
                            [-104.9, 39.7],
                            [-105.1, 39.7]
                        ]]
                    },
                    "properties": {
                        "BASENAME": "Denver",
                        "GEOID": "08031",
                        "STATE": "08"
                    }
                }
            ]
        }'''

        when:
        validator.validateResponseSchema(shortRingPolygon)

        then:
        def e = thrown(SchemaDriftException)
        e.message.contains("Linear ring must contain at least 4 coordinates")
    }

    def "REJECT | should reject out of bounds or non-finite coordinates"() {
        given: "out of bounds longitude 200.0"
        String outOfBoundsGeoJson = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [200.0, 39.7],
                            [-104.9, 39.7],
                            [-104.9, 39.8],
                            [-105.1, 39.8],
                            [200.0, 39.7]
                        ]]
                    },
                    "properties": {
                        "BASENAME": "Denver",
                        "GEOID": "08031",
                        "STATE": "08"
                    }
                }
            ]
        }'''

        when:
        validator.validateResponseSchema(outOfBoundsGeoJson)

        then:
        def e = thrown(SchemaDriftException)
        e.message.contains("Coordinate out of WGS84 bounds")
    }

    @Unroll
    def "REJECT | should detect schema drift when mandatory property #missingProp is missing or blank"(String missingProp, String propsJson) {
        given: "payload missing Census property"
        String payload = """{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [-105.1, 39.7],
                            [-104.9, 39.7],
                            [-104.9, 39.8],
                            [-105.1, 39.8],
                            [-105.1, 39.7]
                        ]]
                    },
                    "properties": ${propsJson}
                }
            ]
        }"""

        when:
        validator.validateResponseSchema(payload)

        then:
        def e = thrown(SchemaDriftException)
        e.message.contains("missing required property '${missingProp}'")

        where:
        missingProp | propsJson
        "BASENAME"  | '{"GEOID": "08031", "STATE": "08"}'
        "GEOID"     | '{"BASENAME": "Denver", "STATE": "08"}'
        "STATE"     | '{"BASENAME": "Denver", "GEOID": "08031"}'
        "BASENAME"  | '{"BASENAME": "", "GEOID": "08031", "STATE": "08"}'
        "GEOID"     | '{"BASENAME": "Denver", "GEOID": "   ", "STATE": "08"}'
    }

    def "STUB_CLIENT | should record intercepted requests, allow reset, and validate contract"() {
        given: "a StubHttpClient with recording enabled"
        StubHttpClient stubClient = new StubHttpClient(
                sendHandler: { req, handler ->
                    new StubHttpResponse<InputStream>(200, new ByteArrayInputStream("{}".getBytes("UTF-8")))
                },
                validateRequestContract: true
        )

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"))
                .header("Accept", "application/json, application/geo+json")
                .header("User-Agent", "Kaiju-GIS-Ingestion/1.0")
                .GET()
                .build()

        when: "sending request through stub client"
        stubClient.send(req, HttpResponse.BodyHandlers.ofInputStream())

        then: "request is recorded and contract is verified"
        stubClient.recordedRequests.size() == 1
        stubClient.lastRequest == req
        noExceptionThrown()

        when: "validating recorded requests explicitly"
        stubClient.validateRecordedRequests()

        then:
        noExceptionThrown()

        when: "client is reset"
        stubClient.reset()

        then: "recorded requests are cleared"
        stubClient.recordedRequests.isEmpty()
        stubClient.lastRequest == null
    }
}
