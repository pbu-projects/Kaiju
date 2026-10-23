package lol.pbu.kaiju.service

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.json.tree.JsonNode
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
import org.locationtech.jts.geom.CoordinateFilter
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
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
        verifyAll {
            service.resolveTolerance(null) == 0.001d
            service.resolveTolerance(0.000001d) == 0.0001d
            service.resolveTolerance(0.1d) == 0.05d
            service.resolveTolerance(0.005d) == 0.005d
        }
    }

    @Unroll
    def "should reject invalid tolerance values: #tolerance"() {
        when:
        service.resolveTolerance(tolerance)

        then:
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_REQUEST
            message.contains(expectedMessage)
        }

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
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?q=onerror=alert(1)",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?q=onload=alert(1)",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?q=../../traversal",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/<bracket",
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/>bracket",
                "https://tigerweb.geo.census.gov",
                "//tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query",
                "https:/arcgis/rest/services/TIGERweb/query",
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
        verifyAll(response) {
            it != null
            name() == "Desc Test Region"
        }

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
        "[1, 2, 3]"                                                                                                | HttpStatus.BAD_REQUEST
        "\"just-a-string\""                                                                                        | HttpStatus.BAD_REQUEST
        "12345"                                                                                                    | HttpStatus.BAD_REQUEST
        "true"                                                                                                     | HttpStatus.BAD_REQUEST
        '{"type": "FeatureCollection", "features": ["not-an-object"]}'                                             | HttpStatus.BAD_REQUEST
        '{"type": "FeatureCollection", "features": [123]}'                                                         | HttpStatus.BAD_REQUEST
        '{"type": "FeatureCollection", "features": [null]}'                                                        | HttpStatus.BAD_REQUEST
        '{"type": "FeatureCollection", "features": [true]}'                                                        | HttpStatus.BAD_REQUEST
        '{"type": "FeatureCollection", "features": [{"type": "Feature", "geometry": null}]}'                       | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-185.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-185.0, 40.0], [-185.0, 39.0]]]}'   | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[185.0, 39.0], [104.0, 39.0], [104.0, 40.0], [185.0, 40.0], [185.0, 39.0]]]}'         | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-105.0, -95.0], [-104.0, -95.0], [-104.0, -80.0], [-105.0, -80.0], [-105.0, -95.0]]]}' | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-105.0, 95.0], [-104.0, 95.0], [-104.0, 80.0], [-105.0, 80.0], [-105.0, 95.0]]]}'     | HttpStatus.BAD_REQUEST
        '{"coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-105.0, 40.0], [-105.0, 39.0]]]}'   | HttpStatus.BAD_REQUEST
        '{"type": 12345, "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-105.0, 40.0], [-105.0, 39.0]]]}' | HttpStatus.BAD_REQUEST
        '{"type": "Polygon"}'                                                                                      | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": "not-an-array"}'                                                       | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [null]}'                                                               | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": ["not-a-ring-array"]}'                                                 | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [12345]}'                                                             | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[1], [2], [3], [1]]]}'                                              | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [1]]]}'             | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[null, [-104.0, 39.0], [-104.0, 40.0], [-105.0, 39.0]]]}'            | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], null]]}'            | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], "not-an-array"]]}'  | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[["a", "b"], ["c", "d"], ["e", "f"], ["a", "b"]]]}'                 | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-105.0, "lat"], [-104.0, 39.0], [-104.0, 40.0], [-105.0, "lat"]]]}' | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[["lon", 39.0], [-104.0, 39.0], [-104.0, 40.0], ["lon", 39.0]]]}'   | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-105.0, "lat"]]]}' | HttpStatus.BAD_REQUEST
        '{"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], ["lon", 39.0]]]}'   | HttpStatus.BAD_REQUEST
        '{"type": "MultiPolygon", "coordinates": ["not-a-polygon-array"]}'                                         | HttpStatus.BAD_REQUEST
        '{"type": "MultiPolygon", "coordinates": [null]}'                                                          | HttpStatus.BAD_REQUEST
        '{"type": "Feature", "geometry": "invalid-geometry-string"}'                                               | HttpStatus.BAD_REQUEST
        '{"type": "Feature", "geometry": 12345}'                                                                  | HttpStatus.BAD_REQUEST
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
        verifyAll(ex) {
            status == HttpStatus.BAD_REQUEST
            message.contains("GeoJSON FeatureCollection contains multiple features")
        }
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
        verifyAll(response) {
            it != null
            regionId() == testRegionId
            name() == "Spec Region"
            originalPoints() == 5
            simplifiedPoints() == 5
            reductionRatio() == "0.00%"
            valid() == true
        }

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
        verifyAll(response) {
            it != null
            regionId() == testRegionId
            name() == "MultiPolygon Region"
            originalPoints() == 10
            valid() == true
        }

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

    def "should reject linear ring with fewer than 4 coordinates before serde can auto-close"() {
        given: "a GeoJSON payload with a 3-coordinate unclosed linear ring"
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
                    }
                }
            ]
        }'''

        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(triangleJson.getBytes("UTF-8")))
        })

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when: "attempting to ingest geometry with fewer than 4 coordinates"
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then: "a 400 Bad Request exception is thrown specifically for ring length"
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_REQUEST
            message.contains("Linear ring must contain at least 4 coordinate positions.")
        }
    }

    def "should initialize default HttpClient when null httpClient is provided"() {
        when:
        def svc = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                new JtsGeometrySerde(),
                null,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        then:
        svc != null
    }

    def "should accept valid URL with explicit port 443"() {
        given:
        UUID testRegionId = UUID.randomUUID()
        executeSql("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Port 443 Region', ST_GeographyFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))", testRegionId)

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
        IngestBoundaryResponse response = customService.ingestBoundary(
                testRegionId,
                new IngestBoundaryCommand("https://tigerweb.geo.census.gov:443/arcgis/rest/services/TIGERweb/query", 0.001)
        )

        then:
        verifyAll(response) {
            it != null
            name() == "Port 443 Region"
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE id = ?", testRegionId)
    }

    def "should successfully ingest single Feature payload with geometry"() {
        given: "a single Feature payload with geometry"
        UUID testRegionId = UUID.randomUUID()
        executeSql("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Feature Region', ST_GeographyFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))", testRegionId)

        String validFeatureJson = '''{
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
        }'''

        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(validFeatureJson.getBytes("UTF-8")))
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
        verifyAll(response) {
            it != null
            regionId() == testRegionId
            name() == "Feature Region"
            valid() == true
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE id = ?", testRegionId)
    }

    def "should handle interrupted exception during download"() {
        given:
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            throw new InterruptedException("Simulated thread interruption")
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
        verifyAll(ex) {
            status == HttpStatus.SERVICE_UNAVAILABLE
            message.contains("Boundary download request was interrupted")
        }
        Thread.interrupted()
    }

    def "should handle IO exception while reading response stream"() {
        given:
        InputStream faultyStream = new InputStream() {
            @Override
            int read() throws IOException {
                throw new IOException("Simulated network stream break")
            }
        }
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, faultyStream)
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
        verifyAll(ex) {
            status == HttpStatus.BAD_GATEWAY
            message.contains("Failed reading boundary response payload")
        }
    }

    def "should throw 400 when geometry serde produces null"() {
        given:
        JtsGeometrySerde nullSerde = new JtsGeometrySerde() {
            @Override
            Geometry deserializeFromNode(JsonNode node) {
                return null
            }
        }
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
                nullSerde,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_REQUEST
            message.contains("GeoJSON payload does not contain a valid geometry object")
        }
    }

    def "should throw 400 when serializing geometry fails"() {
        given:
        ObjectMapper mockMapper = Mock(ObjectMapper) {
            readValue(_ as byte[], _ as Class) >> objectMapper.readValue('{"type": "Polygon", "coordinates": [[[-105.1, 39.7], [-104.9, 39.7], [-104.9, 39.8], [-105.1, 39.8], [-105.1, 39.7]]]}'.getBytes("UTF-8"), JsonNode)
            writeValueAsString(_) >> { throw new IOException("Serialization error") }
        }
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream('{"type": "Polygon", "coordinates": [[[-105.1, 39.7], [-104.9, 39.7], [-104.9, 39.8], [-105.1, 39.8], [-105.1, 39.7]]]}'.getBytes("UTF-8")))
        })
        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                mockMapper,
                new JtsGeometrySerde(),
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_REQUEST
            message.contains("Failed to serialize geometry")
        }
    }

    def "should handle database exceptions and topology invalidation states during update"() {
        given: "a valid polygon payload and client"
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

        when: "database update throws SQLException"
        Connection mockConn1 = Mock(Connection) {
            prepareStatement({ String sql -> sql.contains("simplified") }) >> {
                throw new SQLException("Update failed")
            }
        }
        JdbcOperations mockJdbc1 = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(mockConn1) }
        }
        BoundaryIngestionService svc1 = new BoundaryIngestionService(
                mockJdbc1,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
        svc1.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex1 = thrown(HttpStatusException)
        verifyAll(ex1) {
            status == HttpStatus.INTERNAL_SERVER_ERROR
            message.contains("Database error updating administrative region boundary.")
        }

        when: "verifying region existence throws SQLException"
        PreparedStatement updateStmt2 = Mock(PreparedStatement) {
            executeQuery() >> Mock(ResultSet) {
                next() >> false
            }
        }
        Connection mockConn2 = Mock(Connection) {
            prepareStatement({ String sql -> sql.contains("simplified") }) >> updateStmt2
            prepareStatement({ String sql -> sql.contains("SELECT 1 FROM administrative_regions") }) >> {
                throw new SQLException("Select failed")
            }
        }
        JdbcOperations mockJdbc2 = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(mockConn2) }
        }
        BoundaryIngestionService svc2 = new BoundaryIngestionService(
                mockJdbc2,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
        svc2.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex2 = thrown(HttpStatusException)
        verifyAll(ex2) {
            status == HttpStatus.INTERNAL_SERVER_ERROR
            message.contains("Database error verifying administrative region existence.")
        }

        when: "region exists but update returned zero rows due to invalid resulting geometry"
        PreparedStatement updateStmt3 = Mock(PreparedStatement) {
            executeQuery() >> Mock(ResultSet) {
                next() >> false
            }
        }
        PreparedStatement checkStmt3 = Mock(PreparedStatement) {
            executeQuery() >> Mock(ResultSet) {
                next() >> true
            }
        }
        Connection mockConn3 = Mock(Connection) {
            prepareStatement({ String sql -> sql.contains("simplified") }) >> updateStmt3
            prepareStatement({ String sql -> sql.contains("SELECT 1 FROM administrative_regions") }) >> checkStmt3
        }
        JdbcOperations mockJdbc3 = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(mockConn3) }
        }
        BoundaryIngestionService svc3 = new BoundaryIngestionService(
                mockJdbc3,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
        svc3.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex3 = thrown(HttpStatusException)
        verifyAll(ex3) {
            status == HttpStatus.UNPROCESSABLE_ENTITY
            message.contains("Resulting geometry is topologically invalid")
        }

        when: "update succeeds with orig_pts equal to 0"
        ResultSet rs4 = Mock(ResultSet) {
            next() >> true
            getString("name") >> "Zero Pts Region"
            getInt("orig_pts") >> 0
            getInt("simp_pts") >> 0
            getBoolean("is_valid") >> true
        }
        PreparedStatement updateStmt4 = Mock(PreparedStatement) {
            executeQuery() >> rs4
        }
        Connection mockConn4 = Mock(Connection) {
            prepareStatement({ String sql -> sql.contains("simplified") }) >> updateStmt4
        }
        JdbcOperations mockJdbc4 = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(mockConn4) }
        }
        BoundaryIngestionService svc4 = new BoundaryIngestionService(
                mockJdbc4,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
        IngestBoundaryResponse res4 = svc4.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        verifyAll(res4) {
            it != null
            reductionRatio() == "0.00%"
        }
    }

    @Unroll
    def "should reject geometry containing non-finite coordinate: x=#x, y=#y"() {
        given: "a serde returning a geometry that reports non-finite coordinates on traversal"
        double targetX = x
        double targetY = y
        JtsGeometrySerde nonFiniteSerde = new JtsGeometrySerde() {
            @Override
            Geometry deserializeFromNode(JsonNode node) {
                def gf = getGeometryFactory()
                def ring = gf.createLinearRing([
                        new Coordinate(-105.1, 39.7),
                        new Coordinate(-104.9, 39.7),
                        new Coordinate(-104.9, 39.8),
                        new Coordinate(-105.1, 39.8),
                        new Coordinate(-105.1, 39.7)
                ] as Coordinate[])
                return new NonFinitePolygon(ring, gf, targetX, targetY)
            }
        }
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
                nonFiniteSerde,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_REQUEST
            message.contains("Coordinates must contain valid finite numbers.")
        }

        where:
        x                        | y
        Double.NaN               | 0.0d
        0.0d                     | Double.NaN
        Double.POSITIVE_INFINITY | 0.0d
        0.0d                     | Double.POSITIVE_INFINITY
    }
}

class NonFinitePolygon extends Polygon {
    private final double coordX
    private final double coordY

    NonFinitePolygon(LinearRing shell, GeometryFactory factory, double coordX, double coordY) {
        super(shell, null, factory)
        this.coordX = coordX
        this.coordY = coordY
    }

    @Override
    void apply(CoordinateFilter filter) {
        filter.filter(new Coordinate(coordX, coordY))
    }
}
