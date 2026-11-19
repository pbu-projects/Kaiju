package lol.pbu.kaiju.service

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.dto.IngestBoundaryCommand
import lol.pbu.kaiju.dto.UsSyncResult
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Arrays
import java.util.UUID

@MicronautTest(transactional = false)
class BoundaryAndSyncEdgeCasesSpec extends Specification {

    private static final String VALID_QUERY_URL =
            "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query"

    @Inject
    JdbcOperations jdbcOperations

    @Inject
    ObjectMapper objectMapper

    private BoundaryIngestionService buildIngestionService(StubHttpClient stubClient, JdbcOperations jdbc = jdbcOperations) {
        return new BoundaryIngestionService(
                jdbc,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
    }

    private UsBoundarySyncService buildSyncService(StubHttpClient stubClient, JdbcOperations jdbc = jdbcOperations) {
        return new UsBoundarySyncService(buildIngestionService(stubClient, jdbc), jdbc, objectMapper)
    }

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

    private String queryString(String sql, Object... params) {
        return jdbcOperations.execute { status ->
            Connection conn = status instanceof Connection ? (Connection) status : (Connection) status.getConnection()
            conn.prepareStatement(sql).withCloseable { stmt ->
                params.eachWithIndex { param, idx ->
                    stmt.setObject(idx + 1, param)
                }
                stmt.executeQuery().withCloseable { rs ->
                    return rs.next() ? rs.getString(1) : null
                }
            }
        }
    }

    private UUID queryUuid(String sql, Object... params) {
        return jdbcOperations.execute { status ->
            Connection conn = status instanceof Connection ? (Connection) status : (Connection) status.getConnection()
            conn.prepareStatement(sql).withCloseable { stmt ->
                params.eachWithIndex { param, idx ->
                    stmt.setObject(idx + 1, param)
                }
                stmt.executeQuery().withCloseable { rs ->
                    return rs.next() ? rs.getObject(1, UUID.class) : null
                }
            }
        }
    }

    private Long queryLong(String sql, Object... params) {
        return jdbcOperations.execute { status ->
            Connection conn = status instanceof Connection ? (Connection) status : (Connection) status.getConnection()
            conn.prepareStatement(sql).withCloseable { stmt ->
                params.eachWithIndex { param, idx ->
                    stmt.setObject(idx + 1, param)
                }
                stmt.executeQuery().withCloseable { rs ->
                    return rs.next() ? rs.getLong(1) : null
                }
            }
        }
    }

    @Unroll
    def "rejects unsafe source URL before making any HTTP call: #reason"() {
        given: "an ingestion service wired to a stub HTTP client and mock database"
        StubHttpClient stubClient = new StubHttpClient()
        JdbcOperations mockJdbc = Mock(JdbcOperations)
        BoundaryIngestionService service = buildIngestionService(stubClient, mockJdbc)

        when: "ingestBoundary is called with a disallowed URL"
        service.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand(badUrl, 0.001d))

        then: "it throws 400 Bad Request without calling HTTP or the database"
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_REQUEST
            message.contains(expectedFragment)
        }
        0 * mockJdbc._
        stubClient.recordedRequests.isEmpty()

        where:
        reason                | badUrl                                                                                               | expectedFragment
        "http scheme"         | "http://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query"                                 | "https scheme"
        "ftp scheme"          | "ftp://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query"                                  | "https scheme"
        "metadata IP SSRF"    | "https://169.254.169.254/arcgis/rest/services/TIGERweb/query"                                        | "Host must strictly be"
        "loopback IPv4 SSRF"  | "https://127.0.0.1/arcgis/rest/services/TIGERweb/query"                                              | "Host must strictly be"
        "loopback IPv6 SSRF"  | "https://[::1]/arcgis/rest/services/TIGERweb/query"                                                  | "Host must strictly be"
        "localhost SSRF"      | "https://localhost/arcgis/rest/services/TIGERweb/query"                                              | "Host must strictly be"
        "lookalike host SSRF" | "https://tigerweb.geo.census.gov.evil.example/arcgis/rest/services/TIGERweb/query"                   | "Host must strictly be"
        "userinfo user"       | "https://admin@tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query"                          | "userinfo"
        "userinfo password"   | "https://user:secret@tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query"                    | "userinfo"
        "userinfo host spoof" | "https://tigerweb.geo.census.gov@evil.example/arcgis/rest/services/TIGERweb/query"                   | "userinfo"
        "path traversal"      | "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/../admin/query"                       | "traversal"
        "query traversal"     | "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query?where=../../etc/passwd"         | "traversal"
    }

    @Unroll
    def "rejects HTTP #statusCode from upstream without changing saved region"() {
        given: "a saved region in the database and a stub HTTP client returning an upstream error"
        UUID regionId = UUID.randomUUID()
        String initialWkt = "POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))"
        executeSql(
                "INSERT INTO administrative_regions (id, name, geom, area_sq_m) VALUES (?, 'Protected Region', ST_GeographyFromText(?), 12345.0)",
                regionId,
                initialWkt
        )
        String originalGeomText = queryString("SELECT ST_AsText(geom::geometry) FROM administrative_regions WHERE id = ?", regionId)

        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(statusCode, new ByteArrayInputStream("Upstream Error".getBytes(StandardCharsets.UTF_8)))
        })
        BoundaryIngestionService service = buildIngestionService(stubClient)

        when: "boundary ingestion hits the upstream 5xx error"
        service.ingestBoundary(regionId, new IngestBoundaryCommand(VALID_QUERY_URL, 0.001d))

        then: "it throws 502 Bad Gateway and leaves the stored region untouched"
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_GATEWAY
            message.contains("HTTP status ${statusCode}")
        }
        verifyAll {
            queryString("SELECT name FROM administrative_regions WHERE id = ?", regionId) == "Protected Region"
            queryString("SELECT ST_AsText(geom::geometry) FROM administrative_regions WHERE id = ?", regionId) == originalGeomText
            queryString("SELECT area_sq_m::text FROM administrative_regions WHERE id = ?", regionId).startsWith("12345")
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE id = ?", regionId)

        where:
        statusCode << [500, 502, 503, 504]
    }

    def "rejects boundary payloads larger than 15 MB"() {
        given: "a stream that emits one byte more than MAX_PAYLOAD_BYTES"
        long remaining = BoundaryIngestionService.MAX_PAYLOAD_BYTES + 1L
        InputStream oversizedStream = new InputStream() {
            @Override
            int read() {
                if (remaining <= 0) {
                    return -1
                }
                remaining--
                return (byte) ' '
            }

            @Override
            int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1
                }
                int toCopy = (int) Math.min(len, remaining)
                Arrays.fill(b, off, off + toCopy, (byte) ' ')
                remaining -= toCopy
                return toCopy
            }
        }
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, oversizedStream)
        })
        JdbcOperations mockJdbc = Mock(JdbcOperations)
        BoundaryIngestionService service = buildIngestionService(stubClient, mockJdbc)

        when: "ingestBoundary downloads the oversized response"
        service.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand(VALID_QUERY_URL, 0.001d))

        then: "it stops with 413 Payload Too Large and never writes to the database"
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.REQUEST_ENTITY_TOO_LARGE
            message.contains("15MB")
        }
        0 * mockJdbc._
    }

    @Unroll
    def "rejects #caseName without updating regions"() {
        given: "an upstream GeoJSON payload with invalid feature or polygon ring structure"
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))
        })
        JdbcOperations mockJdbc = Mock(JdbcOperations)
        BoundaryIngestionService service = buildIngestionService(stubClient, mockJdbc)

        when: "ingestBoundary validates the downloaded payload"
        service.ingestBoundary(UUID.randomUUID(), new IngestBoundaryCommand(VALID_QUERY_URL, 0.001d))

        then: "it fails with the expected HTTP error and skips database updates"
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == expectedStatus
            message.contains(expectedFragment)
        }
        0 * mockJdbc._

        where:
        caseName                      | payload                                                                                                                                    | expectedStatus                   | expectedFragment
        "empty feature collection"    | '{"type": "FeatureCollection", "features": []}'                                                                                            | HttpStatus.BAD_REQUEST           | "contains no features"
        "null features array"         | '{"type": "FeatureCollection", "features": null}'                                                                                          | HttpStatus.BAD_REQUEST           | "contains no features"
        "ring with fewer than 4 pts"  | '{"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-105.0, 39.0]]]}'                                                   | HttpStatus.BAD_REQUEST           | "at least 4 coordinate positions"
        "unclosed polygon ring"       | '{"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-105.0, 40.0]]]}'                                   | HttpStatus.BAD_REQUEST           | "must be closed"
        "unclosed multipolygon ring"  | '{"type": "MultiPolygon", "coordinates": [[[[-105.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-105.0, 40.0]]]]}'                            | HttpStatus.BAD_REQUEST           | "must be closed"
        "self-intersecting bowtie"    | '{"type": "Polygon", "coordinates": [[[-105.0, 39.0], [-104.0, 40.0], [-105.0, 40.0], [-104.0, 39.0], [-105.0, 39.0]]]}'                   | HttpStatus.UNPROCESSABLE_ENTITY  | "topologically invalid"
        "out-of-bounds coordinates"   | '{"type": "Polygon", "coordinates": [[[-190.0, 39.0], [-104.0, 39.0], [-104.0, 40.0], [-190.0, 40.0], [-190.0, 39.0]]]}'                   | HttpStatus.BAD_REQUEST           | "WGS84 bounds"
    }

    def "syncs two pages when exceededTransferLimit is true and counts created and updated regions"() {
        given: "Wyoming state and one pre-existing county, plus two pages of TIGERweb county responses"
        String statePayload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "56", "NAME": "Wyoming", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-111.0, 41.0], [-104.0, 41.0], [-104.0, 45.0], [-111.0, 45.0], [-111.0, 41.0]]]
                }
            }]
        }'''
        String existingCountyPayload = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": false,
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "56001", "NAME": "Albany County", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-106.0, 41.0], [-105.2, 41.0], [-105.2, 42.0], [-106.0, 42.0], [-106.0, 41.0]]]
                }
            }]
        }'''
        String page1 = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": true,
            "features": [
                {
                    "type": "Feature",
                    "properties": { "GEOID": "56001", "NAME": "Albany County", "STATE": "56" },
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-106.0, 41.0], [-105.2, 41.0], [-105.2, 42.1], [-106.0, 42.1], [-106.0, 41.0]]]
                    }
                },
                {
                    "type": "Feature",
                    "properties": { "GEOID": "56021", "NAME": "Laramie County", "STATE": "56" },
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-105.2, 41.0], [-104.1, 41.0], [-104.1, 41.6], [-105.2, 41.6], [-105.2, 41.0]]]
                    }
                }
            ]
        }'''
        String page2 = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": false,
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "56025", "NAME": "Natrona County", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-107.5, 42.4], [-106.0, 42.4], [-106.0, 43.5], [-107.5, 43.5], [-107.5, 42.4]]]
                }
            }]
        }'''

        boolean seedMode = true
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            String body
            if (uri.contains("/80/query")) {
                body = statePayload
            } else if (seedMode) {
                body = existingCountyPayload
            } else if (uri.contains("resultOffset=0")) {
                body = page1
            } else {
                body = page2
            }
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        syncService.syncStates(null)
        syncService.syncStateCounties("WY", null)
        stubClient.recordedRequests.clear()
        seedMode = false

        when: "syncStateCounties runs across both pages"
        UsSyncResult result = syncService.syncStateCounties("WY", null)

        then: "it requests offset 0 then offset 2 and reports 2 created and 1 updated"
        verifyAll(result) {
            stateCode() == "WY"
            level() == 2
            created() == 2
            updated() == 1
            skipped() == 0
            failed() == 0
            errors().isEmpty()
        }
        verifyAll {
            stubClient.recordedRequests.size() == 2
            stubClient.recordedRequests[0].uri().toString().contains("resultOffset=0")
            stubClient.recordedRequests[1].uri().toString().contains("resultOffset=2")
            queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56001'") != null
            queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56021'") != null
            queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56025'") != null
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid IN ('56001', '56021', '56025')")
        executeSql("DELETE FROM administrative_regions WHERE geoid = '56'")
    }

    def "fails cleanly on mid-sync 5xx error without corrupting state or wedging later syncs"() {
        given: "Wyoming state plus a 2-page county sync where page 2 returns HTTP 503 on the first run"
        String statePayload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "56", "NAME": "Wyoming", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-111.0, 41.0], [-104.0, 41.0], [-104.0, 45.0], [-111.0, 45.0], [-111.0, 41.0]]]
                }
            }]
        }'''
        String page1 = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": true,
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "56001", "NAME": "Albany County", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-106.0, 41.0], [-105.2, 41.0], [-105.2, 42.0], [-106.0, 42.0], [-106.0, 41.0]]]
                }
            }]
        }'''
        String page2 = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": false,
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "56021", "NAME": "Laramie County", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-105.2, 41.0], [-104.1, 41.0], [-104.1, 41.6], [-105.2, 41.6], [-105.2, 41.0]]]
                }
            }]
        }'''

        boolean failPage2 = true
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            if (uri.contains("/80/query")) {
                return new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(statePayload.getBytes(StandardCharsets.UTF_8)))
            }
            if (uri.contains("resultOffset=0")) {
                return new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(page1.getBytes(StandardCharsets.UTF_8)))
            }
            if (failPage2) {
                return new StubHttpResponse<InputStream>(503, new ByteArrayInputStream("Service Unavailable".getBytes(StandardCharsets.UTF_8)))
            }
            return new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(page2.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        syncService.syncStates(null)
        UUID wyId = queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56'")
        long auditCountBefore = queryLong("SELECT COUNT(*) FROM administrative_region_audit_logs WHERE region_id = ?", wyId)

        when: "the first sync fails on page 2 with HTTP 503"
        syncService.syncStateCounties("WY", null)

        then: "it throws 502 Bad Gateway, leaves page 2 uncreated, and writes no audit log"
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.BAD_GATEWAY
            message.contains("HTTP status 503")
        }
        verifyAll {
            queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56001'") != null
            queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56021'") == null
            queryLong("SELECT COUNT(*) FROM administrative_region_audit_logs WHERE region_id = ?", wyId) == auditCountBefore
        }

        when: "the upstream recovers and the sync is retried"
        failPage2 = false
        UsSyncResult retryResult = syncService.syncStateCounties("WY", null)

        then: "the concurrency lock was released and the retry finishes cleanly"
        verifyAll(retryResult) {
            stateCode() == "WY"
            level() == 2
            created() == 1
            updated() == 1
            failed() == 0
            errors().isEmpty()
        }
        queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56021'") != null

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid IN ('56001', '56021')")
        executeSql("DELETE FROM administrative_regions WHERE geoid = '56'")
    }

    def "propagates 500 error when recording the sync audit log hits a SQLException"() {
        given: "a valid state sync response and a database connection whose audit insert throws SQLException"
        String statePayload = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": false,
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "56", "NAME": "Wyoming", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-111.0, 41.0], [-104.0, 41.0], [-104.0, 45.0], [-111.0, 45.0], [-111.0, 41.0]]]
                }
            }]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(statePayload.getBytes(StandardCharsets.UTF_8)))
        })

        ResultSet upsertRs = Mock(ResultSet) {
            next() >> true
            getBoolean("inserted") >> true
        }
        PreparedStatement upsertStmt = Mock(PreparedStatement) {
            executeQuery() >> upsertRs
        }
        PreparedStatement auditStmt = Mock(PreparedStatement) {
            executeUpdate() >> { throw new SQLException("Audit insert failed") }
        }
        Connection mockConn = Mock(Connection) {
            prepareStatement({ String sql -> sql.contains("administrative_regions") }) >> upsertStmt
            prepareStatement({ String sql -> sql.contains("administrative_region_audit_logs") }) >> auditStmt
        }
        JdbcOperations mockJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(mockConn) }
        }
        UsBoundarySyncService syncService = buildSyncService(stubClient, mockJdbc)

        when: "syncStates runs with an actor user ID so recordAuditLog executes"
        syncService.syncStates(UUID.randomUUID())

        then: "the SQLException is translated to 500 Internal Server Error and never swallowed"
        def ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.INTERNAL_SERVER_ERROR
            message.contains("Failed recording boundary sync audit log")
        }
    }
}
