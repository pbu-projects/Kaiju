package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import lol.pbu.kaiju.dto.IngestBoundaryCommand
import lol.pbu.kaiju.dto.IngestBoundaryResponse
import spock.lang.Unroll

import io.micronaut.context.annotation.Property

import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class BoundaryIngestionSpec extends BaseControllerSpec {

    private UUID testRegionId

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

        when: "a regional manager or global admin requests boundary ingestion via HTTP POST"
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

    def "SECURITY | should return 403 Forbidden when unauthorized user attempts boundary ingestion"() {
        given: "a standard user without administrative claims"
        String validUrl = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/82/query?where=BASENAME%3D%27Denver%27+AND+STATE%3D%2708%27&outFields=*&f=geojson"
        def command = new IngestBoundaryCommand(validUrl, null)

        when: "a standard user attempts ingestion"
        def standardUserRequest = authenticated(
                HttpRequest.POST("/administrative-regions/${testRegionId}/boundary/ingest", command),
                UUID.randomUUID().toString(),
                ["STANDARD_USER"]
        )
        client.exchange(standardUserRequest, IngestBoundaryResponse)

        then: "a 403 Forbidden exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }
}
