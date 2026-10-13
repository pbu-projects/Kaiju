package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import lol.pbu.kaiju.dto.IngestBoundaryCommand
import lol.pbu.kaiju.dto.IngestBoundaryResponse
import lol.pbu.kaiju.service.StubHttpClient
import lol.pbu.kaiju.service.StubHttpResponse
import spock.lang.Unroll

import java.net.http.HttpClient as JavaHttpClient
import java.net.http.HttpRequest as JavaHttpRequest
import java.net.http.HttpResponse as JavaHttpResponse
import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class BoundaryIngestionSpec extends BaseControllerSpec {

    private UUID testRegionId

    @MockBean(JavaHttpClient)
    JavaHttpClient javaHttpClient() {
        new StubHttpClient(sendHandler: { JavaHttpRequest req, JavaHttpResponse.BodyHandler handler ->
            if (req.uri().toString().contains("Denver") || req.uri().toString().contains("tigerweb")) {
                InputStream fixtureStream = getClass().getResourceAsStream("/fixtures/denver-county-tigerweb.json")
                if (fixtureStream == null) {
                    throw new IllegalStateException("Fixture /fixtures/denver-county-tigerweb.json not found")
                }
                return new StubHttpResponse<InputStream>(200, fixtureStream)
            }
            return new StubHttpResponse<InputStream>(404, new ByteArrayInputStream(new byte[0]))
        })
    }

    protected <T> MutableHttpRequest<T> asRegionalAdmin(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, ["REGION_DIRECTOR", "region:manage"])
    }

    protected <T> MutableHttpRequest<T> asStandardUser(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, ["STANDARD_USER"])
    }

    def setup() {
        testRegionId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Ingestion Test Region', ST_GeographyFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))
        """, testRegionId)
    }

    def cleanup() {
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

        and: "the updated geometry in PostGIS is valid and simplified"
        def dbRow = sql.firstRow("SELECT ST_IsValid(geom::geometry) AS is_valid, ST_NPoints(geom::geometry) AS pts FROM administrative_regions WHERE id = ?", [testRegionId])
        dbRow.is_valid == true
        dbRow.pts == body.simplifiedPoints()
    }

    def "SUCCESS | should permit valid query parameters containing words like description"() {
        given: "a query URL with outFields containing DESCRIPTION"
        String denverUrlWithDescription = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27+AND+STATE%3D%2708%27&outFields=NAME,DESCRIPTION&f=geojson"
        def command = new IngestBoundaryCommand(denverUrlWithDescription, 0.001)

        when: "a global admin requests boundary ingestion"
        def request = asGlobalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command))
        def response = client.exchange(request, IngestBoundaryResponse)

        then: "the request succeeds with 200 OK"
        response.status == HttpStatus.OK
        response.body() != null
        response.body().regionId() == testRegionId
        response.body().valid() == true
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
        client.exchange(asRegionalAdmin(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 403 Forbidden exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "SECURITY | should return 403 Forbidden when standard user attempts boundary ingestion"() {
        given: "a standard user without administrative claims"
        String validUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27+AND+STATE%3D%2708%27&outFields=*&f=geojson"
        def command = new IngestBoundaryCommand(validUrl, null)

        when: "a standard user attempts boundary ingestion"
        client.exchange(asStandardUser(HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command)), IngestBoundaryResponse)

        then: "a 403 Forbidden exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }
}
