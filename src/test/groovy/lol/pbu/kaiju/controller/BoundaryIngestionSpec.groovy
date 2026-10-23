package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import lol.pbu.kaiju.dto.IngestBoundaryCommand
import lol.pbu.kaiju.dto.IngestBoundaryResponse
import lol.pbu.kaiju.service.StubHttpClient
import lol.pbu.kaiju.service.StubHttpResponse
import spock.lang.Unroll

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.http.HttpClient as JavaHttpClient
import java.net.http.HttpRequest as JavaHttpRequest
import java.net.http.HttpResponse as JavaHttpResponse
import java.util.Collections
import java.util.UUID

@MicronautTest(transactional = false)
class BoundaryIngestionSpec extends BaseControllerSpec {

    private UUID testRegionId

    private static final Closure DEFAULT_HANDLER = { JavaHttpRequest req, JavaHttpResponse.BodyHandler handler ->
        if (req.uri().toString().contains("Denver") || req.uri().toString().contains("tigerweb")) {
            InputStream fixtureStream = BoundaryIngestionSpec.class.getResourceAsStream("/fixtures/denver-county-tigerweb.json")
            if (fixtureStream == null) {
                throw new IllegalStateException("Fixture /fixtures/denver-county-tigerweb.json not found")
            }
            return new StubHttpResponse<InputStream>(200, fixtureStream)
        }
        return new StubHttpResponse<InputStream>(404, new ByteArrayInputStream(new byte[0]))
    }

    private final StubHttpClient stubHttpClientInstance = new StubHttpClient(
            sendHandler: DEFAULT_HANDLER,
            defaultSendHandler: DEFAULT_HANDLER,
            validateRequestContract: true
    )

    @MockBean(JavaHttpClient)
    JavaHttpClient javaHttpClient() {
        return stubHttpClientInstance
    }

    def setup() {
        stubHttpClientInstance.reset()
        testRegionId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Ingestion Test Region', ST_GeographyFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))
        """, testRegionId)
    }

    def cleanup() {
        stubHttpClientInstance.reset()
        if (testRegionId) {
            executeUpdate("DELETE FROM administrative_regions WHERE id = ?", testRegionId)
        }
    }

    def "SUCCESS | should successfully ingest, simplify, and validate boundary from authoritative TIGERweb API"() {
        given: "a valid administrative region and authoritative Census TIGERweb query URL for Denver County"
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27+AND+STATE%3D%2708%27&outFields=*&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "a global admin requests boundary ingestion via HTTP POST"
        def request = asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command))
        def response = client.exchange(request, IngestBoundaryResponse)
        IngestBoundaryResponse body = response.body()

        then: "the endpoint responds with 200 OK and valid ingestion metrics"
        verifyAll {
            response.status == HttpStatus.OK
            body != null
            body.regionId() == testRegionId
            body.name() == "Ingestion Test Region"
            body.originalPoints() > 1000
            body.simplifiedPoints() > 0
            body.simplifiedPoints() < body.originalPoints()
            body.reductionRatio() != null
            body.reductionRatio().contains("%")
            body.valid() == true
        }

        and: "the updated geometry in PostGIS is valid and simplified"
        def dbRow = sql.firstRow("SELECT ST_IsValid(geom::geometry) AS is_valid, ST_NPoints(geom::geometry) AS pts FROM administrative_regions WHERE id = ?", [testRegionId])
        verifyAll(dbRow) {
            is_valid == true
            pts == body.simplifiedPoints()
        }

        and: "the mock HTTP client recorded the intercepted request and confirmed the contract"
        verifyAll {
            stubHttpClientInstance.recordedRequests.size() == 1
            stubHttpClientInstance.lastRequest.method() == "GET"
            stubHttpClientInstance.lastRequest.uri().toString().contains("tigerweb.geo.census.gov")
        }
    }

    def "SUCCESS | should permit valid query parameters containing words like description"() {
        given: "a query URL with outFields containing DESCRIPTION"
        String denverUrlWithDescription = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27+AND+STATE%3D%2708%27&outFields=NAME,DESCRIPTION&f=geojson"
        def command = new IngestBoundaryCommand(denverUrlWithDescription, 0.001)

        when: "a global admin requests boundary ingestion"
        def request = asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command))
        def response = client.exchange(request, IngestBoundaryResponse)

        then: "the request succeeds with 200 OK"
        verifyAll(response) {
            status == HttpStatus.OK
            body() != null
            body().regionId() == testRegionId
            body().valid() == true
        }
    }

    def "REJECT | should reject insecure HTTP scheme with 400 Bad Request"() {
        given: "a command using unencrypted HTTP instead of HTTPS"
        String insecureUrl = "http://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?f=geojson"
        def command = new IngestBoundaryCommand(insecureUrl, null)

        when: "attempting boundary ingestion with insecure HTTP"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 400 Bad Request response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    @Unroll
    def "REJECT | should reject SSRF attempt targeting unauthorized host: #maliciousUrl"(String maliciousUrl) {
        given: "a command targeting an unverified host"
        def command = new IngestBoundaryCommand(maliciousUrl, null)

        when: "attempting ingestion against an unauthorized host"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the request is blocked with 400 Bad Request"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        maliciousUrl << [
                "https://malicious-site.com/arcgis/rest/services/TIGERweb/County/query?f=geojson",
                "https://127.0.0.1/arcgis/rest/services/TIGERweb/internal/admin",
                "https://169.254.169.254/arcgis/rest/services/TIGERweb/latest/meta-data",
                "https://tigerweb.geo.census.gov.attacker.com/arcgis/rest/services/TIGERweb/fake",
                "https://fake-tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/fake"
        ]
    }

    def "REJECT | should reject non-standard port with 400 Bad Request"() {
        given: "a command targeting an alternate port"
        String badPortUrl = "https://tigerweb.geo.census.gov:8443/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?f=geojson"
        def command = new IngestBoundaryCommand(badPortUrl, null)

        when: "attempting boundary ingestion with non-standard port"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    @Unroll
    def "REJECT | should reject invalid path or path traversal: #badPathUrl"(String badPathUrl) {
        given: "a command with an unapproved path or traversal pattern"
        def command = new IngestBoundaryCommand(badPathUrl, null)

        when: "attempting ingestion with path violation"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        badPathUrl << [
                "https://tigerweb.geo.census.gov/etc/passwd",
                "https://tigerweb.geo.census.gov/api/internal",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/../admin/query?f=geojson",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/<script>alert(1)</script>"
        ]
    }

    @Unroll
    def "REJECT | should reject script injection in query parameters: #injectedUrl"(String injectedUrl) {
        given: "a command containing script injection or event handlers in query parameters"
        def command = new IngestBoundaryCommand(injectedUrl, null)

        when: "attempting boundary ingestion"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        injectedUrl << [
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?<script>alert(1)</script>",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?f=geojson&where=javascript:alert(1)",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?f=geojson&search=%3Cscript%3E",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?f=geojson&onload=eval()"
        ]
    }

    @Unroll
    def "REJECT | should reject non-finite or non-positive tolerance: #badTolerance"(Double badTolerance) {
        given: "a command with an invalid tolerance value"
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27+AND+STATE%3D%2708%27&outFields=*&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, badTolerance)

        when: "attempting boundary ingestion with invalid tolerance"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        badTolerance << [Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0d, -0.01d]
    }

    def "REJECT | should return 404 Not Found when administrative region does not exist"() {
        given: "a non-existent administrative region ID"
        UUID missingId = UUID.randomUUID()
        String validUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27+AND+STATE%3D%2708%27&outFields=*&f=geojson"
        def command = new IngestBoundaryCommand(validUrl, null)

        when: "attempting ingestion on a missing region"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${missingId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 404 Not Found is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "REJECT | should return 400 Bad Request when source URL is blank"() {
        given: "a command with blank source URL"
        def command = new IngestBoundaryCommand("", null)

        when: "attempting ingestion"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "SECURITY | should return 403 Forbidden when regional director attempts boundary ingestion"() {
        given: "a regional director with region:manage claim"
        String validUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27+AND+STATE%3D%2708%27&outFields=*&f=geojson"
        def command = new IngestBoundaryCommand(validUrl, null)

        when: "a regional director attempts boundary ingestion"
        client.exchange(asRegionDirector(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 403 Forbidden exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "SECURITY | should return 403 Forbidden when standard user attempts boundary ingestion"() {
        given: "a standard user without administrative claims"
        String validUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27+AND+STATE%3D%2708%27&outFields=*&f=geojson"
        def command = new IngestBoundaryCommand(validUrl, null)

        when: "a standard user attempts boundary ingestion"
        client.exchange(asVolunteer(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 403 Forbidden exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    @Unroll
    def "FAILURE | should return 502 Bad Gateway when upstream census service returns #upstreamStatus error"(int upstreamStatus) {
        given: "upstream Census TIGERweb API returns a server error"
        stubHttpClientInstance.sendHandler = { req, handler ->
            new StubHttpResponse<InputStream>(upstreamStatus, new ByteArrayInputStream("Server Error".getBytes("UTF-8")))
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion through HTTP endpoint"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the controller returns 502 Bad Gateway"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_GATEWAY

        where:
        upstreamStatus << [500, 503]
    }

    def "FAILURE | should return 502 Bad Gateway when upstream connection is dropped or network fails"() {
        given: "upstream connection drops with an IOException"
        stubHttpClientInstance.sendHandler = { req, handler ->
            throw new IOException("Connection abruptly closed by remote server")
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion through HTTP endpoint"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the controller returns 502 Bad Gateway"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_GATEWAY
    }

    def "FAILURE | should return 413 Payload Too Large when upstream payload exceeds 15MB"() {
        given: "upstream returns a stream larger than 15MB"
        byte[] chunk = new byte[1024 * 1024]
        InputStream oversizedStream = new SequenceInputStream(Collections.enumeration((1..16).collect { new ByteArrayInputStream(chunk) }))
        stubHttpClientInstance.sendHandler = { req, handler ->
            new StubHttpResponse<InputStream>(200, oversizedStream)
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion through HTTP endpoint"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the controller returns 413 Payload Too Large"
        def e = thrown(HttpClientResponseException)
        verifyAll(e) {
            status == HttpStatus.REQUEST_ENTITY_TOO_LARGE
            status.code == 413
        }
    }

    def "FAILURE | should return 400 Bad Request when upstream payload is malformed non-JSON"() {
        given: "upstream returns malformed HTML error rather than JSON"
        stubHttpClientInstance.sendHandler = { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream("<html><body>Error from census gateway</body></html>".getBytes("UTF-8")))
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion through HTTP endpoint"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the controller returns 400 Bad Request"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "FAILURE | should return 400 Bad Request when upstream returns empty FeatureCollection"() {
        given: "upstream returns a FeatureCollection with zero features"
        String emptyJson = '{"type": "FeatureCollection", "features": []}'
        stubHttpClientInstance.sendHandler = { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(emptyJson.getBytes("UTF-8")))
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion through HTTP endpoint"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the controller returns 400 Bad Request"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "FAILURE | should return 400 Bad Request when upstream returns multiple features in FeatureCollection"() {
        given: "upstream returns multiple features instead of a single region feature"
        String multiFeatureJson = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-105.1, 39.7], [-104.9, 39.7], [-104.9, 39.8], [-105.1, 39.8], [-105.1, 39.7]]]
                    },
                    "properties": {"BASENAME": "Denver", "GEOID": "08031", "STATE": "08"}
                },
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-105.2, 39.7], [-105.0, 39.7], [-105.0, 39.8], [-105.2, 39.8], [-105.2, 39.7]]]
                    },
                    "properties": {"BASENAME": "Boulder", "GEOID": "08013", "STATE": "08"}
                }
            ]
        }'''
        stubHttpClientInstance.sendHandler = { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(multiFeatureJson.getBytes("UTF-8")))
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion through HTTP endpoint"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the controller returns 400 Bad Request"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "FAILURE | should return 422 Unprocessable Entity when geometry has self-intersecting invalid topology"() {
        given: "upstream returns a self-intersecting bowtie polygon"
        String bowtieJson = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [-105.0, 39.0],
                            [-104.0, 40.0],
                            [-105.0, 40.0],
                            [-104.0, 39.0],
                            [-105.0, 39.0]
                        ]]
                    },
                    "properties": {"BASENAME": "Denver", "GEOID": "08031", "STATE": "08"}
                }
            ]
        }'''
        stubHttpClientInstance.sendHandler = { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(bowtieJson.getBytes("UTF-8")))
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion through HTTP endpoint"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the controller returns 422 Unprocessable Entity"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNPROCESSABLE_ENTITY
    }

    def "SCHEMA_DRIFT | should detect and reject schema drift when upstream response is missing mandatory properties"() {
        given: "schema validation is activated and upstream response is missing the BASENAME attribute"
        stubHttpClientInstance.validateResponseContract = true
        String driftedMissingPropJson = '''{
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
                        "GEOID": "08031",
                        "STATE": "08"
                    }
                }
            ]
        }'''
        stubHttpClientInstance.sendHandler = { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(driftedMissingPropJson.getBytes("UTF-8")))
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion with drifted upstream schema"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the ingestion is blocked by schema validation error"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.INTERNAL_SERVER_ERROR
    }

    def "SCHEMA_DRIFT | should detect and reject schema drift when upstream returns invalid geometry type"() {
        given: "schema validation is activated and upstream response returns Point instead of Polygon"
        stubHttpClientInstance.validateResponseContract = true
        String driftedPointJson = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Point",
                        "coordinates": [-105.1, 39.7]
                    },
                    "properties": {
                        "BASENAME": "Denver",
                        "GEOID": "08031",
                        "STATE": "08"
                    }
                }
            ]
        }'''
        stubHttpClientInstance.sendHandler = { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(driftedPointJson.getBytes("UTF-8")))
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion with invalid geometry type"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the ingestion is blocked by schema validation error"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.INTERNAL_SERVER_ERROR
    }

    def "SCHEMA_DRIFT | should detect and reject schema drift when linear ring is unclosed"() {
        given: "schema validation is activated and upstream response has an unclosed linear ring"
        stubHttpClientInstance.validateResponseContract = true
        String unclosedRingJson = '''{
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
        stubHttpClientInstance.sendHandler = { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(unclosedRingJson.getBytes("UTF-8")))
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion with unclosed ring"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the ingestion is blocked by schema validation error"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.INTERNAL_SERVER_ERROR
    }

    def "FAILURE | should return 400 Bad Request when linear ring has fewer than 4 coordinates"() {
        given: "upstream returns a triangle with only 3 coordinates (unclosed)"
        String triangleJson = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [-105.1, 39.7],
                            [-104.9, 39.7],
                            [-104.9, 39.8]
                        ]]
                    },
                    "properties": {"BASENAME": "Denver", "GEOID": "08031", "STATE": "08"}
                }
            ]
        }'''
        stubHttpClientInstance.sendHandler = { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(triangleJson.getBytes("UTF-8")))
        }
        String denverUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27&f=geojson"
        def command = new IngestBoundaryCommand(denverUrl, 0.001)

        when: "attempting boundary ingestion through HTTP endpoint"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "the controller returns 400 Bad Request"
        def e = thrown(HttpClientResponseException)
        verifyAll(e) {
            status == HttpStatus.BAD_REQUEST
            response.getBody(String).orElse("").contains("Linear ring must contain at least 4 coordinate positions.")
        }
    }
}
