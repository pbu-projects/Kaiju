package lol.pbu.kaiju.service

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.dto.UsSyncResult
import lol.pbu.kaiju.repository.LocationRepository
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.util.UUID

@MicronautTest(transactional = false)
class UsBoundarySyncServiceSpec extends Specification {

    @Inject
    JdbcOperations jdbcOperations

    @Inject
    ObjectMapper objectMapper

    @Inject
    LocationRepository locationRepository

    @Inject
    AdministrativeRegionService administrativeRegionService

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)

    private UsBoundarySyncService buildSyncService(StubHttpClient stubClient) {
        BoundaryIngestionService ingestionService = new BoundaryIngestionService(
                jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
        return new UsBoundarySyncService(ingestionService, jdbcOperations, objectMapper)
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

    private Double queryDouble(String sql, Object... params) {
        return jdbcOperations.execute { status ->
            Connection conn = status instanceof Connection ? (Connection) status : (Connection) status.getConnection()
            conn.prepareStatement(sql).withCloseable { stmt ->
                params.eachWithIndex { param, idx ->
                    stmt.setObject(idx + 1, param)
                }
                stmt.executeQuery().withCloseable { rs ->
                    return rs.next() ? rs.getDouble(1) : null
                }
            }
        }
    }

    def "should sync states and preserve existing Colorado seed name on geoid conflict"() {
        given: "a TIGERweb state payload for Colorado and Hawaii"
        String originalColoradoName = queryString("SELECT name FROM administrative_regions WHERE geoid = '08'")
        String payload = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": false,
            "features": [
                {
                    "type": "Feature",
                    "properties": { "GEOID": "08", "NAME": "Colorado", "STATE": "08" },
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [-109.05, 37.0], [-102.04, 37.0], [-102.04, 41.0], [-109.05, 41.0], [-109.05, 37.0]
                        ]]
                    }
                },
                {
                    "type": "Feature",
                    "properties": { "GEOID": "15", "NAME": "Hawaii", "STATE": "15" },
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[
                            [-158.3, 21.2], [-157.6, 21.2], [-157.6, 21.7], [-158.3, 21.7], [-158.3, 21.2]
                        ]]
                    }
                }
            ]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)

        when: "syncStates runs without an actor user"
        UsSyncResult result = syncService.syncStates(null)

        then: "Colorado is updated while preserving its seed name, and Hawaii is created"
        verifyAll(result) {
            stateCode() == "US"
            level() == 1
            created() == 1
            updated() == 1
            failed() == 0
            errors().isEmpty()
        }
        verifyAll {
            queryString("SELECT name FROM administrative_regions WHERE geoid = '08'") == originalColoradoName
            queryString("SELECT time_zone FROM administrative_regions WHERE geoid = '15'") == "Pacific/Honolulu"
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid = '15'")
    }

    def "should paginate counties when exceededTransferLimit is true"() {
        given: "a state region for Wyoming and two pages of county results"
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
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            String body
            if (uri.contains("/80/query")) {
                body = statePayload
            } else if (uri.contains("resultOffset=0")) {
                body = page1
            } else {
                body = page2
            }
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        syncService.syncStates(null)

        when: "syncStateCounties runs for WY"
        UsSyncResult result = syncService.syncStateCounties("WY", null)

        then: "both pages are ingested under Wyoming"
        verifyAll(result) {
            stateCode() == "WY"
            level() == 2
            created() == 2
            updated() == 0
            failed() == 0
        }
        UUID wyId = queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56'")
        verifyAll {
            queryUuid("SELECT parent_region_id FROM administrative_regions WHERE geoid = '56001'") == wyId
            queryUuid("SELECT parent_region_id FROM administrative_regions WHERE geoid = '56021'") == wyId
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid IN ('56001', '56021')")
        executeSql("DELETE FROM administrative_regions WHERE geoid = '56'")
    }

    def "should assign multi-county city to largest overlap county and fall back to state for independent city"() {
        given: "a state and two counties in Wyoming"
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
        String countiesPayload = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": { "GEOID": "56011", "NAME": "West County", "STATE": "56" },
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-106.0, 41.0], [-105.0, 41.0], [-105.0, 42.0], [-106.0, 42.0], [-106.0, 41.0]]]
                    }
                },
                {
                    "type": "Feature",
                    "properties": { "GEOID": "56012", "NAME": "East County", "STATE": "56" },
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-105.0, 41.0], [-104.0, 41.0], [-104.0, 42.0], [-105.0, 42.0], [-105.0, 41.0]]]
                    }
                }
            ]
        }'''
        String placesPayload = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": { "GEOID": "5690001", "NAME": "Split City", "STATE": "56" },
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-105.1, 41.2], [-104.2, 41.2], [-104.2, 41.8], [-105.1, 41.8], [-105.1, 41.2]]]
                    }
                },
                {
                    "type": "Feature",
                    "properties": { "GEOID": "5690002", "NAME": "Independent City", "STATE": "56" },
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-109.5, 43.2], [-109.2, 43.2], [-109.2, 43.5], [-109.5, 43.5], [-109.5, 43.2]]]
                    }
                }
            ]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            String body
            if (uri.contains("/80/query")) {
                body = statePayload
            } else if (uri.contains("/82/query")) {
                body = countiesPayload
            } else {
                body = placesPayload
            }
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        syncService.syncStates(null)
        syncService.syncStateCounties("WY", null)

        when: "syncStatePlaces runs for WY"
        UsSyncResult result = syncService.syncStatePlaces("WY", false, null)

        then: "Split City picks East County (larger overlap) and Independent City falls back to Wyoming"
        verifyAll(result) {
            stateCode() == "WY"
            level() == 3
            created() == 2
            failed() == 0
        }
        UUID wyId = queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56'")
        UUID eastCountyId = queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56012'")
        verifyAll {
            queryUuid("SELECT parent_region_id FROM administrative_regions WHERE geoid = '5690001'") == eastCountyId
            queryUuid("SELECT parent_region_id FROM administrative_regions WHERE geoid = '5690002'") == wyId
            queryDouble("SELECT area_sq_m FROM administrative_regions WHERE geoid = '56012'") > 1_000_000.0d
            queryDouble("SELECT area_sq_m FROM administrative_regions WHERE geoid = '5690001'") > 1_000_000.0d
            queryDouble("SELECT area_sq_m FROM administrative_regions WHERE geoid = '5690002'") > 1_000_000.0d
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid IN ('5690001', '5690002')")
        executeSql("DELETE FROM administrative_regions WHERE geoid IN ('56011', '56012')")
        executeSql("DELETE FROM administrative_regions WHERE geoid = '56'")
    }

    def "should automatically query layer 30 CDPs for Hawaii"() {
        given: "Hawaii state and CDP payload on layer 30"
        String statePayload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "15", "NAME": "Hawaii", "STATE": "15" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-158.3, 21.2], [-157.6, 21.2], [-157.6, 21.7], [-158.3, 21.7], [-158.3, 21.2]]]
                }
            }]
        }'''
        String emptyPlaces = '{"type": "FeatureCollection", "features": []}'
        String cdpPayload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "1571550", "NAME": "Urban Honolulu", "STATE": "15" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-157.9, 21.28], [-157.8, 21.28], [-157.8, 21.35], [-157.9, 21.35], [-157.9, 21.28]]]
                }
            }]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            String body
            if (uri.contains("/80/query")) {
                body = statePayload
            } else if (uri.contains("/30/query")) {
                body = cdpPayload
            } else {
                body = emptyPlaces
            }
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        syncService.syncStates(null)

        when: "syncStatePlaces is called for HI with includeCdps = false"
        UsSyncResult result = syncService.syncStatePlaces("HI", false, null)

        then: "layer 30 CDP is still synced for Hawaii"
        verifyAll(result) {
            stateCode() == "HI"
            level() == 3
            created() == 1
            failed() == 0
        }
        queryString("SELECT time_zone FROM administrative_regions WHERE geoid = '1571550'") == "Pacific/Honolulu"

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid IN ('1571550', '15')")
    }

    def "should reject unknown state code"() {
        given:
        UsBoundarySyncService syncService = buildSyncService(new StubHttpClient())

        when:
        syncService.syncStateCounties("ZZ", null)

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.BAD_REQUEST
    }

    @Unroll
    def "should resolve timezone #expectedTz for (#lon, #lat)"() {
        given:
        def point = geometryFactory.createPoint(new Coordinate(lon, lat))

        when:
        Optional<String> tz = locationRepository.findTimezoneByPoint(point)

        then:
        verifyAll(tz) {
            isPresent()
            get() == expectedTz
        }

        where:
        lon      | lat    | expectedTz
        -112.074 | 33.448 | "America/Phoenix"
        -179.0   | 52.0   | "America/Adak"
        179.0    | 52.0   | "America/Adak"
        -157.858 | 21.307 | "Pacific/Honolulu"
        -104.990 | 39.739 | "America/Denver"
    }

    def "should exclude city that only touches neighboring county border in getCities"() {
        given: "Wyoming, two adjacent counties, and a city inside East County sharing the border edge with West County"
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
        String countiesPayload = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": { "GEOID": "56011", "NAME": "West County", "STATE": "56" },
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-106.0, 41.0], [-105.0, 41.0], [-105.0, 42.0], [-106.0, 42.0], [-106.0, 41.0]]]
                    }
                },
                {
                    "type": "Feature",
                    "properties": { "GEOID": "56012", "NAME": "East County", "STATE": "56" },
                    "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[-105.0, 41.0], [-104.0, 41.0], [-104.0, 42.0], [-105.0, 42.0], [-105.0, 41.0]]]
                    }
                }
            ]
        }'''
        String placesPayload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "5690003", "NAME": "Border City", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-105.0, 41.2], [-104.5, 41.2], [-104.5, 41.8], [-105.0, 41.8], [-105.0, 41.2]]]
                }
            }]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            String body
            if (uri.contains("/80/query")) {
                body = statePayload
            } else if (uri.contains("/82/query")) {
                body = countiesPayload
            } else {
                body = placesPayload
            }
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        syncService.syncStates(null)
        syncService.syncStateCounties("WY", null)
        syncService.syncStatePlaces("WY", false, null)

        when: "querying cities for each county"
        def eastCities = administrativeRegionService.getCities("WY", "East County")
        def westCities = administrativeRegionService.getCities("WY", "West County")

        then: "Border City is returned for East County but excluded from neighboring West County"
        verifyAll {
            eastCities*.name == ["Border City"]
            westCities.isEmpty()
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid = '5690003'")
        executeSql("DELETE FROM administrative_regions WHERE geoid IN ('56011', '56012')")
        executeSql("DELETE FROM administrative_regions WHERE geoid = '56'")
    }

    def "should fall back to state when independent city touches county border without interior overlap"() {
        given: "Wyoming, West County, and an independent city sharing only West County's eastern border"
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
        String countiesPayload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "56011", "NAME": "West County", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-106.0, 41.0], [-105.0, 41.0], [-105.0, 42.0], [-106.0, 42.0], [-106.0, 41.0]]]
                }
            }]
        }'''
        String placesPayload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "5690004", "NAME": "Touching Independent City", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-105.0, 41.2], [-104.7, 41.2], [-104.7, 41.8], [-105.0, 41.8], [-105.0, 41.2]]]
                }
            }]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            String body
            if (uri.contains("/80/query")) {
                body = statePayload
            } else if (uri.contains("/82/query")) {
                body = countiesPayload
            } else {
                body = placesPayload
            }
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        syncService.syncStates(null)
        syncService.syncStateCounties("WY", null)

        when: "syncStatePlaces runs for WY"
        UsSyncResult result = syncService.syncStatePlaces("WY", false, null)

        then: "Touching Independent City falls back to Wyoming and stores area in square meters"
        UUID wyId = queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56'")
        verifyAll {
            result.created() == 1
            result.failed() == 0
            queryUuid("SELECT parent_region_id FROM administrative_regions WHERE geoid = '5690004'") == wyId
            queryDouble("SELECT area_sq_m FROM administrative_regions WHERE geoid = '5690004'") > 1_000_000.0d
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid = '5690004'")
        executeSql("DELETE FROM administrative_regions WHERE geoid = '56011'")
        executeSql("DELETE FROM administrative_regions WHERE geoid = '56'")
    }
}
