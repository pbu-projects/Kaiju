package lol.pbu.kaiju.service

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.dto.IngestBoundaryCommand
import lol.pbu.kaiju.dto.IngestBoundaryResponse
import spock.lang.Specification
import spock.lang.Unroll

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import lol.pbu.kaiju.serde.JtsGeometrySerde
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Polygon
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@MicronautTest(transactional = false)
class BoundaryIngestionServiceSpec extends Specification {

    @Inject
    BoundaryIngestionService service

    @Inject
    ObjectMapper objectMapper

    @Inject
    JdbcOperations jdbcOperations

    private void executeSql(String sql, Object... params) {
        jdbcOperations.execute { status ->
            Connection conn = status instanceof Connection ? (Connection) status : (Connection) status.getConnection()
            conn.prepareStatement(sql).withCloseable { stmt ->
                params.eachWithIndex { param, idx ->
                    stmt.setObject(idx + 1, param)
                }
                stmt.executeUpdate()
            }
        }
    }



    def "should validate regionId and command null checks"() {
        when: "regionId is null"
        service.ingestBoundary(null, new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/test", 0.001))

        then:
        def e1 = thrown(HttpStatusException)
        e1.status == HttpStatus.BAD_REQUEST

        when: "command is null"
        service.ingestBoundary(UUID.randomUUID(), null)

        then:
        def e2 = thrown(HttpStatusException)
        e2.status == HttpStatus.BAD_REQUEST

        when: "sourceUrl is blank"
        service.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("   ", 0.001))

        then:
        def e3 = thrown(HttpStatusException)
        e3.status == HttpStatus.BAD_REQUEST
    }

    def "should clamp tolerance values properly"() {
        expect:
        service.resolveTolerance(null) == 0.001d
        service.resolveTolerance(0.000001d) == 0.0001d
        service.resolveTolerance(0.1d) == 0.05d
        service.resolveTolerance(0.005d) == 0.005d
    }

    @Unroll
    def "should reject invalid tolerance values: #tolerance"() {
        when:
        service.resolveTolerance(tolerance)

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.BAD_REQUEST
        ex.message.contains(expectedMessage)

        where:
        tolerance                | expectedMessage
        Double.NaN               | "Tolerance must be a finite, positive number."
        Double.POSITIVE_INFINITY | "Tolerance must be a finite, positive number."
        Double.NEGATIVE_INFINITY | "Tolerance must be a finite, positive number."
        0.0d                     | "Tolerance must be greater than zero."
        -0.001d                  | "Tolerance must be greater than zero."
    }

    @Unroll
    def "should reject invalid tolerance through ingestBoundary: #badTolerance"() {
        when:
        service.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", badTolerance))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.BAD_REQUEST

        where:
        badTolerance << [Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0d, -0.05d]
    }

    def "should validate and reject disallowed source URLs"() {
        when:
        service.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand(url, 0.001))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.BAD_REQUEST

        where:
        url << [
                "http://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query",
                "https://malicious.example.com/arcgis/rest/services/TIGERweb/query",
                "https://tigerweb.geo.census.gov:8080/arcgis/rest/services/TIGERweb/query",
                "https://tigerweb.geo.census.gov/other/service/path",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/../escaped",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?q=<script>alert(1)</script>",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?q=javascript:alert(1)",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?q=%3cscript%3e",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?q=path\\escape",
                "ht!tp://invalid syntax url"
        ]
    }

    def "should permit valid query parameters containing words like description"() {
        given: "a query URL containing description in parameter list"
        String urlWithDescription = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?outFields=NAME,DESCRIPTION&f=geojson"
        String validGeoJson = '''{
            "type": "Polygon",
            "coordinates": [[
                [-105.1, 39.7],
                [-104.9, 39.7],
                [-104.9, 39.8],
                [-105.1, 39.8],
                [-105.1, 39.7]
            ]]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(validGeoJson.getBytes("UTF-8")))
        })
        UUID testRegionId = UUID.randomUUID()
        executeSql("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Desc Test Region', ST_GeographyFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))", testRegionId)

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        IngestBoundaryResponse response = customService.ingestBoundary(testRegionId, new IngestBoundaryCommand(urlWithDescription, 0.001))

        then:
        response != null
        response.name() == "Desc Test Region"

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE id = ?", testRegionId)
    }

    def "should reject invalid geojson structures"() {
        given: "a stub HTTP client returning specific payload"
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(jsonPayload.getBytes("UTF-8")))
        })

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when: "payload is processed"
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == expectedStatus

        where:
        jsonPayload                                                                                                | expectedStatus
        "not json at all"                                                                                          | HttpStatus.BAD_REQUEST
        '{"type": "FeatureCollection", "features": []}'                                                            | HttpStatus.BAD_REQUEST
        '{"type": "FeatureCollection", "features": [{}]}'                                                          | HttpStatus.BAD_REQUEST
        '{"type": "FeatureCollection", "features": [{"geometry": {"type": "Point", "coordinates": [1, 2]}}]}'     | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": []}'                                                                   | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[]]}'                                                                 | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[1]]]}'                                                              | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[Double.NaN, 39.0]]]}'                                              | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[200.0, 39.0]]]}'                                                    | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[["invalid", 39.0]]]}'                                                | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-105.0, 39.0]]]}'                  | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-105.0, 40.0]]]}'   | HttpStatus.BAD_REQUEST
        '{"type": "FeatureCollection", "features": [{"geometry": {"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-105.0, 40.0], [-105.0, 39.0]]]}}, {"geometry": {"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-105.0, 40.0], [-105.0, 39.0]]]}}]}' | HttpStatus.BAD_REQUEST
    }

    def "should reject multi-feature FeatureCollection with 400 Bad Request"() {
        given: "a stub HTTP client returning a FeatureCollection with multiple features"
        String multiFeaturePayload = """{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-105.0, 40.0], [-105.0, 39.0]]]
                    }
                },
                {
                    "type": "Feature",
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-105.1, 39.1], [-104.1, 39.1], [-104.1, 40.1], [-105.1, 40.1], [-105.1, 39.1]]]
                    }
                }
            ]
        }"""
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(multiFeaturePayload.getBytes("UTF-8")))
        })

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when: "attempting to ingest a multi-feature FeatureCollection"
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then: "a 400 Bad Request exception is thrown with appropriate explanation"
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.BAD_REQUEST
        ex.message.contains("GeoJSON FeatureCollection contains multiple features")
    }

    def "should enforce payload size limits"() {
        given: "a stub HTTP client returning stream exceeding 15MB"
        byte[] chunk = new byte[1024 * 1024]
        InputStream oversizedStream = new SequenceInputStream(Collections.enumeration((1..16).collect { new ByteArrayInputStream(chunk) }))

        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, oversizedStream)
        })

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.REQUEST_ENTITY_TOO_LARGE
    }

    def "should handle upstream errors and connection failures"() {
        given: "a stub HTTP client returning 500 error"
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(500, new ByteArrayInputStream("Server Error".getBytes("UTF-8")))
        })

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when: "upstream returns 500"
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex1 = thrown(HttpStatusException)
        ex1.status == HttpStatus.BAD_GATEWAY

        when: "network exception occurs"
        stubClient.sendHandler = { req, handler ->
            throw new IOException("Connection reset")
        }
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex2 = thrown(HttpStatusException)
        ex2.status == HttpStatus.BAD_GATEWAY
    }

    def "should reject invalid topology when database update yields invalid geometry"() {
        given: "a stub HTTP client returning a self-intersecting bowtie polygon"
        String bowtieJson = '''{
            "type": "Polygon",
            "coordinates": [[
                [-105.0, 39.0],
                [-104.0, 40.0],
                [-105.0, 40.0],
                [-104.0, 39.0],
                [-105.0, 39.0]
            ]]
        }'''

        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(bowtieJson.getBytes("UTF-8")))
        })

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        UUID existingRegionId = UUID.fromString("a0000000-0000-0000-0000-000000000002")

        when:
        customService.ingestBoundary(existingRegionId, new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.UNPROCESSABLE_ENTITY
    }

    def "should successfully ingest valid polygon and update region in database"() {
        given: "a test region in the database"
        UUID testRegionId = UUID.randomUUID()
        executeSql("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Spec Region', ST_GeographyFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))", testRegionId)

        String validGeoJson = '''{
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
                    }
                }
            ]
        }'''

        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(validGeoJson.getBytes("UTF-8")))
        })

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        IngestBoundaryResponse response = customService.ingestBoundary(testRegionId, new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        response != null
        response.regionId() == testRegionId
        response.name() == "Spec Region"
        response.originalPoints() == 5
        response.simplifiedPoints() == 5
        response.reductionRatio() == "0.00%"
        response.valid() == true

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE id = ?", testRegionId)
    }

    def "should successfully ingest MultiPolygon boundary"() {
        given: "a test region in the database"
        UUID testRegionId = UUID.randomUUID()
        executeSql("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'MultiPolygon Region', ST_GeographyFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))", testRegionId)

        String validMultiGeoJson = '''{
            "type": "MultiPolygon",
            "coordinates": [
                [[
                    [-105.1, 39.7],
                    [-104.9, 39.7],
                    [-104.9, 39.8],
                    [-105.1, 39.8],
                    [-105.1, 39.7]
                ]],
                [[
                    [-104.8, 39.7],
                    [-104.6, 39.7],
                    [-104.6, 39.8],
                    [-104.8, 39.8],
                    [-104.8, 39.7]
                ]]
            ]
        }'''

        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(validMultiGeoJson.getBytes("UTF-8")))
        })

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        IngestBoundaryResponse response = customService.ingestBoundary(testRegionId, new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        response != null
        response.regionId() == testRegionId
        response.name() == "MultiPolygon Region"
        response.originalPoints() == 10
        response.valid() == true

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE id = ?", testRegionId)
    }

    def "should return 404 when administrative region does not exist in database"() {
        given: "a non-existent region UUID"
        UUID missingRegionId = UUID.randomUUID()
        String validGeoJson = '''{
            "type": "Polygon",
            "coordinates": [[
                [-105.1, 39.7],
                [-104.9, 39.7],
                [-104.9, 39.8],
                [-105.1, 39.8],
                [-105.1, 39.7]
            ]]
        }'''

        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(validGeoJson.getBytes("UTF-8")))
        })

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        customService.ingestBoundary(missingRegionId, new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.NOT_FOUND
    }
}
