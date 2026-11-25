package lol.pbu.kaiju.service

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.json.tree.JsonNode
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.dto.IngestBoundaryRequest
import lol.pbu.kaiju.dto.IngestBoundaryResponse
import spock.lang.Specification
import spock.lang.Unroll

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
import java.util.UUID

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



    @Unroll
    def "should reject boundary import when #scenario"() {
        when:
        service.ingestBoundary(regionId, request)

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.BAD_REQUEST

        where:
        scenario             | regionId          | request
        "regionId is null"   | null              | new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/test", 0.001)
        "request is null"    | UUID.randomUUID() | null
        "sourceUrl is blank" | UUID.randomUUID() | new IngestBoundaryRequest("   ", 0.001)
    }

    @Unroll
    def "should clamp tolerance #input to #expected"() {
        expect:
        service.resolveTolerance(input) == expected

        where:
        input     || expected
        null      || 0.001d
        0.000001d || 0.0001d
        0.1d      || 0.05d
        0.005d    || 0.005d
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
        service.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", badTolerance))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.BAD_REQUEST

        where:
        badTolerance << [Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0d, -0.05d]
    }

    @Unroll
    def "should validate and reject disallowed source URL: #url"() {
        when:
        service.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest(url, 0.001))

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
                "ht!tp://invalid syntax url",
                "https://user@tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query",
                "https://user:pass@tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query"
        ]
    }

    def "should clamp tiny antimeridian longitude overshoot and ingest successfully"() {
        given: "a GeoJSON polygon with tiny 180.0000001 and -180.0000001 overshoot"
        String geoJsonWithOvershoot = '''{
            "type": "Polygon",
            "coordinates": [[
                [179.5, 51.5],
                [180.0000001, 51.5],
                [180.0000001, 52.0],
                [179.5, 52.0],
                [179.5, 51.5]
            ]]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(geoJsonWithOvershoot.getBytes("UTF-8")))
        })
        UUID testRegionId = UUID.randomUUID()
        executeSql("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Adak Test Region', ST_GeographyFromText('POLYGON((179.5 51.5, 180.0 51.5, 180.0 52.0, 179.5 52.0, 179.5 51.5))'))", testRegionId)

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
                new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001)
        )

        then:
        verifyAll(response) {
            it != null
            regionId() == testRegionId
            name() == "Adak Test Region"
            valid()
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE id = ?", testRegionId)
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
        IngestBoundaryResponse response = customService.ingestBoundary(testRegionId, new IngestBoundaryRequest(urlWithDescription, 0.001))

        then:
        verifyAll(response) {
            it != null
            name() == "Desc Test Region"
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE id = ?", testRegionId)
    }

    @Unroll
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
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

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
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

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
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.REQUEST_ENTITY_TOO_LARGE
    }

    def "should return bad gateway when boundary download returns a server error"() {
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
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.BAD_GATEWAY
    }

    def "should return bad gateway when boundary download fails with a network error"() {
        given: "a stub HTTP client throwing an IOException"
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            throw new IOException("Connection reset")
        })

        BoundaryIngestionService customService = new BoundaryIngestionService(
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when: "network exception occurs"
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.BAD_GATEWAY
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
        customService.ingestBoundary(existingRegionId, new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

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
        IngestBoundaryResponse response = customService.ingestBoundary(testRegionId, new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

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
        IngestBoundaryResponse response = customService.ingestBoundary(testRegionId, new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

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
        customService.ingestBoundary(missingRegionId, new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.NOT_FOUND
    }

    def "should reject a boundary ring with fewer than four points"() {
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
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

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
                new IngestBoundaryRequest("https://tigerweb.geo.census.gov:443/arcgis/rest/services/TIGERweb/query", 0.001)
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
        IngestBoundaryResponse response = customService.ingestBoundary(testRegionId, new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

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
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

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
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_GATEWAY
            message.contains("Failed reading boundary response payload")
        }
    }

    def "should reject a boundary payload when no shape can be read"() {
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
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

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
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_REQUEST
            message.contains("Failed to serialize geometry")
        }
    }

    private BoundaryIngestionService buildMockedJdbcIngestionService(JdbcOperations jdbcOps) {
        new BoundaryIngestionService(
                jdbcOps,
                objectMapper,
                validGeoJsonStubClient(),
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
    }

    def "should return internal server error when saving the boundary fails in the database"() {
        given: "a JDBC connection that fails on boundary update"
        Connection mockConn = Mock(Connection) {
            prepareStatement({ String sql -> sql.contains("simplified") }) >> {
                throw new SQLException("Update failed")
            }
        }
        JdbcOperations mockJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(mockConn) }
        }
        BoundaryIngestionService svc = buildMockedJdbcIngestionService(mockJdbc)

        when: "database update throws SQLException"
        svc.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.INTERNAL_SERVER_ERROR
            message.contains("Database error updating administrative region boundary.")
        }
    }

    def "should return internal server error when checking if the region exists fails in the database"() {
        given: "a JDBC connection that fails when checking region existence"
        PreparedStatement updateStmt = Mock(PreparedStatement) {
            executeQuery() >> Mock(ResultSet) {
                next() >> false
            }
        }
        Connection mockConn = Mock(Connection) {
            prepareStatement({ String sql -> sql.contains("simplified") }) >> updateStmt
            prepareStatement({ String sql -> sql.contains("SELECT 1 FROM administrative_regions") }) >> {
                throw new SQLException("Select failed")
            }
        }
        JdbcOperations mockJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(mockConn) }
        }
        BoundaryIngestionService svc = buildMockedJdbcIngestionService(mockJdbc)

        when: "verifying region existence throws SQLException"
        svc.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.INTERNAL_SERVER_ERROR
            message.contains("Database error verifying administrative region existence.")
        }
    }

    def "should return unprocessable entity when the simplified boundary is invalid"() {
        given: "a region that exists but produces no updated row due to invalid resulting geometry"
        PreparedStatement updateStmt = Mock(PreparedStatement) {
            executeQuery() >> Mock(ResultSet) {
                next() >> false
            }
        }
        PreparedStatement checkStmt = Mock(PreparedStatement) {
            executeQuery() >> Mock(ResultSet) {
                next() >> true
            }
        }
        Connection mockConn = Mock(Connection) {
            prepareStatement({ String sql -> sql.contains("simplified") }) >> updateStmt
            prepareStatement({ String sql -> sql.contains("SELECT 1 FROM administrative_regions") }) >> checkStmt
        }
        JdbcOperations mockJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(mockConn) }
        }
        BoundaryIngestionService svc = buildMockedJdbcIngestionService(mockJdbc)

        when: "ingesting the boundary"
        svc.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.UNPROCESSABLE_ENTITY
            message.contains("Resulting geometry is topologically invalid")
        }
    }

    def "should report zero percent reduction when the original boundary has zero points"() {
        given: "a database update returning zero original points"
        ResultSet rs = Mock(ResultSet) {
            next() >> true
            getString("name") >> "Zero Pts Region"
            getInt("orig_pts") >> 0
            getInt("simp_pts") >> 0
            getBoolean("is_valid") >> true
        }
        PreparedStatement updateStmt = Mock(PreparedStatement) {
            executeQuery() >> rs
        }
        Connection mockConn = Mock(Connection) {
            prepareStatement({ String sql -> sql.contains("simplified") }) >> updateStmt
        }
        JdbcOperations mockJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(mockConn) }
        }
        BoundaryIngestionService svc = buildMockedJdbcIngestionService(mockJdbc)

        when: "ingesting the boundary"
        IngestBoundaryResponse res = svc.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        verifyAll(res) {
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
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

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

    @Unroll
    def "should reject geometry containing out-of-bounds WGS84 coordinate: x=#x, y=#y"() {
        given: "a serde returning a geometry with out-of-bounds coordinates on traversal"
        double targetX = x
        double targetY = y
        JtsGeometrySerde outOfBoundsSerde = new JtsGeometrySerde() {
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
                outOfBoundsSerde,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        customService.ingestBoundary(UUID.randomUUID(), new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001))

        then:
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_REQUEST
            message.contains("Coordinates out of valid WGS84 bounds")
        }

        where:
        x      | y
        -185.0d | 39.0d
        185.0d  | 39.0d
        -105.0d | -95.0d
        -105.0d | 95.0d
    }

    private static final String VALID_POLYGON_GEOJSON = '''{
        "type": "Polygon",
        "coordinates": [[
            [-105.1, 39.7],
            [-104.9, 39.7],
            [-104.9, 39.8],
            [-105.1, 39.8],
            [-105.1, 39.7]
        ]]
    }'''

    private BoundaryIngestionService buildSixArgIngestionService(StubHttpClient stubClient) {
        return new BoundaryIngestionService(
                null,
                service.jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
    }

    private StubHttpClient validGeoJsonStubClient() {
        return new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(VALID_POLYGON_GEOJSON.getBytes("UTF-8")))
        })
    }

    def "six-arg constructor wires jdbcOperations"() {
        given: "a service built with the six-arg constructor"
        BoundaryIngestionService sixArgService = buildSixArgIngestionService(validGeoJsonStubClient())

        expect: "jdbcOperations matches the injected instance"
        sixArgService.getJdbcOperations() === service.jdbcOperations
    }

    def "should clamp negative antimeridian overshoot to -180.0 during boundary ingestion"() {
        given: "a polygon with negative antimeridian overshoot -180.0000001"
        String negativeOvershootGeoJson = '''{
            "type": "Polygon",
            "coordinates": [[
                [-180.0000001, 51.5],
                [-179.5, 51.5],
                [-179.5, 52.0],
                [-180.0000001, 52.0],
                [-180.0000001, 51.5]
            ]]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(negativeOvershootGeoJson.getBytes("UTF-8")))
        })
        UUID testRegionId = UUID.randomUUID()
        executeSql("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Negative Antimeridian Region', ST_GeographyFromText('POLYGON((-180.0 51.5, -179.5 51.5, -179.5 52.0, -180.0 52.0, -180.0 51.5))'))", testRegionId)
        BoundaryIngestionService sixArgService = buildSixArgIngestionService(stubClient)

        when: "ingesting polygon with negative antimeridian overshoot"
        IngestBoundaryResponse overshootRes = sixArgService.ingestBoundary(
                testRegionId,
                new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001)
        )

        then: "ingestion succeeds and clamps -180.0000001 to -180.0"
        verifyAll(overshootRes) {
            it != null
            regionId() == testRegionId
            name() == "Negative Antimeridian Region"
            valid()
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE id = ?", testRegionId)
    }

    @Unroll
    def "should reject boundary downloads when #scenario"() {
        given:
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream((responseBody ?: "").getBytes("UTF-8")))
        })
        BoundaryIngestionService sixArgService = buildSixArgIngestionService(stubClient)

        when:
        sixArgService.fetchAndParseJson(url)

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.BAD_REQUEST

        where:
        scenario           | url                                                                   | responseBody
        "url is null"      | null                                                                  | null
        "url is blank"     | "   "                                                                 | null
        "JSON is invalid"  | "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query" | "not-json"
        "JSON is an array" | "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query" | "[1, 2, 3]"
    }

    @Unroll
    def "should reject a boundary feature when #scenario"() {
        given:
        BoundaryIngestionService sixArgService = buildSixArgIngestionService(new StubHttpClient())
        JsonNode node = rawJson == null ? null : objectMapper.readValue(rawJson.getBytes("UTF-8"), JsonNode)

        when:
        sixArgService.parseAndValidateFeatureGeometry(node)

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.BAD_REQUEST

        where:
        scenario                | rawJson
        "feature is null"       | null
        "feature is non-object" | "[1, 2]"
        "geometry is missing"   | '{"type": "Feature"}'
        "geometry is null"      | '{"type": "Feature", "geometry": null}'
    }

    def "should reject ingestBoundary with null actorUserId when administrativeRegionService is configured"() {
        given:
        BoundaryIngestionService auditedService = new BoundaryIngestionService(
                Mock(AdministrativeRegionService),
                service.jdbcOperations,
                objectMapper,
                validGeoJsonStubClient(),
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        auditedService.ingestBoundary(
                UUID.randomUUID(),
                new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001),
                null
        )

        then:
        thrown(IllegalArgumentException)
    }

    def "should delegate ingestBoundary to administrativeRegionService when actorUserId is provided"() {
        given:
        AdministrativeRegionService mockRegionService = Mock(AdministrativeRegionService)
        BoundaryIngestionService auditedService = new BoundaryIngestionService(
                mockRegionService,
                service.jdbcOperations,
                objectMapper,
                validGeoJsonStubClient(),
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
        UUID regionIdArg = UUID.randomUUID()
        UUID actorIdArg = UUID.randomUUID()
        IngestBoundaryResponse delegatedResp = new IngestBoundaryResponse(regionIdArg, "Delegated", 5, 5, "0.00%", true)
        mockRegionService.applyIngestedBoundary(
                regionIdArg,
                _ as String,
                0.001d,
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query",
                actorIdArg
        ) >> delegatedResp

        when:
        IngestBoundaryResponse actualDelegated = auditedService.ingestBoundary(
                regionIdArg,
                new IngestBoundaryRequest("https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query", 0.001),
                actorIdArg
        )

        then:
        actualDelegated == delegatedResp
    }

    def "should return 422 when upsertRegionByGeoid returns zero rows"() {
        given:
        PreparedStatement emptyStmt = Mock(PreparedStatement) {
            executeQuery() >> Mock(ResultSet) { next() >> false }
        }
        Connection emptyConn = Mock(Connection) {
            prepareStatement(_) >> emptyStmt
        }
        JdbcOperations emptyJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(emptyConn) }
        }
        BoundaryIngestionService upsertEmptySvc = new BoundaryIngestionService(
                emptyJdbc,
                objectMapper,
                validGeoJsonStubClient(),
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        upsertEmptySvc.upsertRegionByGeoid(new BoundaryIngestionService.RegionUpsert(
                "99", "Invalid", null, 1, "US", "ZZ", VALID_POLYGON_GEOJSON, 0.001d
        ))

        then:
        HttpStatusException invalidRegionEx = thrown()
        invalidRegionEx.status == HttpStatus.UNPROCESSABLE_ENTITY
    }

    def "should return 422 when upsertPlaceByGeoid returns zero rows"() {
        given:
        PreparedStatement emptyStmt = Mock(PreparedStatement) {
            executeQuery() >> Mock(ResultSet) { next() >> false }
        }
        Connection emptyConn = Mock(Connection) {
            prepareStatement(_) >> emptyStmt
        }
        JdbcOperations emptyJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(emptyConn) }
        }
        BoundaryIngestionService upsertEmptySvc = new BoundaryIngestionService(
                emptyJdbc,
                objectMapper,
                validGeoJsonStubClient(),
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        upsertEmptySvc.upsertPlaceByGeoid("9900001", "Invalid Place", UUID.randomUUID(), "ZZ", VALID_POLYGON_GEOJSON, 0.001d)

        then:
        HttpStatusException invalidPlaceEx = thrown()
        invalidPlaceEx.status == HttpStatus.UNPROCESSABLE_ENTITY
    }

    def "should return 500 when upsertRegionByGeoid encounters a SQLException"() {
        given:
        Connection sqlErrConn = Mock(Connection) {
            prepareStatement(_) >> { throw new SQLException("Upsert region SQL error") }
        }
        JdbcOperations sqlErrJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(sqlErrConn) }
        }
        BoundaryIngestionService upsertErrSvc = new BoundaryIngestionService(
                sqlErrJdbc,
                objectMapper,
                validGeoJsonStubClient(),
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        upsertErrSvc.upsertRegionByGeoid(new BoundaryIngestionService.RegionUpsert(
                "99", "Error", null, 1, "US", "ZZ", VALID_POLYGON_GEOJSON, 0.001d
        ))

        then:
        HttpStatusException sqlRegionEx = thrown()
        sqlRegionEx.status == HttpStatus.INTERNAL_SERVER_ERROR
    }

    def "should return 500 when upsertPlaceByGeoid encounters a SQLException"() {
        given:
        Connection sqlErrConn = Mock(Connection) {
            prepareStatement(_) >> { throw new SQLException("Upsert place SQL error") }
        }
        JdbcOperations sqlErrJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(sqlErrConn) }
        }
        BoundaryIngestionService upsertErrSvc = new BoundaryIngestionService(
                sqlErrJdbc,
                objectMapper,
                validGeoJsonStubClient(),
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )

        when:
        upsertErrSvc.upsertPlaceByGeoid("9900001", "Error Place", UUID.randomUUID(), "ZZ", VALID_POLYGON_GEOJSON, 0.001d)

        then:
        HttpStatusException sqlPlaceEx = thrown()
        sqlPlaceEx.status == HttpStatus.INTERNAL_SERVER_ERROR
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
