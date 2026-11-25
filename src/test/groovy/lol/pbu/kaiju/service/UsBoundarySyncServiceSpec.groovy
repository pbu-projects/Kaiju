package lol.pbu.kaiju.service

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.json.tree.JsonNode
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

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

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

    private UsBoundarySyncService setupWyomingWithCounties(String placesPayload) {
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
        return syncService
    }

    def "assigns a city spanning two counties to the county with the larger overlap"() {
        given: "a state, two counties, and a city overlapping mostly East County"
        String placesPayload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "5690001", "NAME": "Split City", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-105.1, 41.2], [-104.2, 41.2], [-104.2, 41.8], [-105.1, 41.8], [-105.1, 41.2]]]
                }
            }]
        }'''
        UsBoundarySyncService syncService = setupWyomingWithCounties(placesPayload)

        when: "syncStatePlaces runs for WY"
        UsSyncResult result = syncService.syncStatePlaces("WY", false, null)

        then: "Split City picks East County and records area sizes"
        verifyAll(result) {
            stateCode() == "WY"
            level() == 3
            created() == 1
            failed() == 0
        }
        UUID eastCountyId = queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56012'")
        verifyAll {
            queryUuid("SELECT parent_region_id FROM administrative_regions WHERE geoid = '5690001'") == eastCountyId
            queryDouble("SELECT area_sq_m FROM administrative_regions WHERE geoid = '56012'") > 1_000_000.0d
            queryDouble("SELECT area_sq_m FROM administrative_regions WHERE geoid = '5690001'") > 1_000_000.0d
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid = '5690001'")
        executeSql("DELETE FROM administrative_regions WHERE geoid IN ('56011', '56012')")
        executeSql("DELETE FROM administrative_regions WHERE geoid = '56'")
    }

    def "assigns an independent city with no county overlap directly to the state"() {
        given: "a state, two counties, and a city outside both counties"
        String placesPayload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "5690002", "NAME": "Independent City", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-109.5, 43.2], [-109.2, 43.2], [-109.2, 43.5], [-109.5, 43.5], [-109.5, 43.2]]]
                }
            }]
        }'''
        UsBoundarySyncService syncService = setupWyomingWithCounties(placesPayload)

        when: "syncStatePlaces runs for WY"
        UsSyncResult result = syncService.syncStatePlaces("WY", false, null)

        then: "Independent City falls back to Wyoming as parent"
        verifyAll(result) {
            stateCode() == "WY"
            level() == 3
            created() == 1
            failed() == 0
        }
        UUID wyId = queryUuid("SELECT id FROM administrative_regions WHERE geoid = '56'")
        verifyAll {
            queryUuid("SELECT parent_region_id FROM administrative_regions WHERE geoid = '5690002'") == wyId
            queryDouble("SELECT area_sq_m FROM administrative_regions WHERE geoid = '5690002'") > 1_000_000.0d
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid = '5690002'")
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

    def "updates a state using its state code when GEOID is missing and more-pages flag is false"() {
        given: "a state payload with STATE code fallback and nested transfer limit set to false"
        String payload = '''{
            "type": "FeatureCollection",
            "properties": { "exceededTransferLimit": false },
            "features": [{
                "type": "Feature",
                "properties": { "STATE": "08", "NAME": "Colorado" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                        [-109.05, 37.0], [-102.04, 37.0], [-102.04, 41.0], [-109.05, 41.0], [-109.05, 37.0]
                    ]]
                }
            }]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)

        when: "syncStates runs"
        UsSyncResult result = syncService.syncStates(null)

        then: "Colorado updates via STATE fallback"
        verifyAll(result) {
            stateCode() == "US"
            level() == 1
            updated() == 1
            skipped() == 0
            failed() == 0
        }
    }

    def "skips an unknown state code during national state sync"() {
        given: "a state payload with an unknown state code"
        String payload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "99", "NAME": "Unknown Territory" },
                "geometry": null
            }]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)

        when: "syncStates runs"
        UsSyncResult result = syncService.syncStates(null)

        then: "the unknown code is skipped"
        verifyAll(result) {
            stateCode() == "US"
            level() == 1
            updated() == 0
            skipped() == 1
            failed() == 0
        }
    }

    def "records a failure when a known state has no boundary shape during national state sync"() {
        given: "a state payload for Hawaii with null geometry"
        String payload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "15", "NAME": "Hawaii" },
                "geometry": null
            }]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)

        when: "syncStates runs"
        UsSyncResult result = syncService.syncStates(null)

        then: "the Hawaii failure is recorded"
        verifyAll(result) {
            stateCode() == "US"
            level() == 1
            updated() == 0
            skipped() == 0
            failed() == 1
            errors().size() == 1
            errors()[0].startsWith("HI:")
        }
    }

    private static final String WYOMING_STATE_PAYLOAD = '''{
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

    private UsBoundarySyncService buildTrailingSlashSyncService(StubHttpClient stubClient) {
        BoundaryIngestionService ingestionService = new BoundaryIngestionService(
                jdbcOperations,
                objectMapper,
                stubClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
        return new UsBoundarySyncService(
                ingestionService,
                jdbcOperations,
                objectMapper,
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer/",
                80, 82, 28, 30
        )
    }

    def "should skip malformed county features and record geometry errors in syncStateCounties"() {
        given: "Wyoming state and county payloads with missing fields and null geometry"
        String countiesPayload = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": { "GEOID": "56099" },
                    "geometry": null
                },
                {
                    "type": "Feature",
                    "properties": { "NAME": "Missing Geoid County" },
                    "geometry": null
                },
                {
                    "type": "Feature",
                    "properties": { "GEOID": "56098", "NAME": "Broken County" },
                    "geometry": null
                }
            ]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            String body = uri.contains("/80/query") ? WYOMING_STATE_PAYLOAD : countiesPayload
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        syncService.syncStates(null)

        when: "syncStateCounties runs for WY"
        UsSyncResult countyResult = syncService.syncStateCounties("WY", null)

        then: "incomplete county features are skipped and geometry errors are recorded"
        verifyAll(countyResult) {
            created() == 0
            skipped() == 2
            failed() == 1
            errors().size() == 1
            errors()[0].startsWith("56098:")
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid = '56'")
    }

    def "should skip malformed place features and record geometry errors in syncStatePlaces"() {
        given: "Wyoming state and place payloads with malformed properties and null geometry"
        String placesPayload = '''{
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": null
                },
                {
                    "type": "Feature",
                    "properties": { "GEOID": 12345, "NAME": "Numeric Geoid" },
                    "geometry": null
                },
                {
                    "type": "Feature",
                    "properties": { "GEOID": "5699998" },
                    "geometry": null
                },
                {
                    "type": "Feature",
                    "properties": { "GEOID": "5699999", "NAME": "Broken Place" },
                    "geometry": null
                }
            ]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            String body = uri.contains("/80/query") ? WYOMING_STATE_PAYLOAD : placesPayload
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        syncService.syncStates(null)

        when: "syncStatePlaces runs with includeCdps set to false"
        UsSyncResult placeResult = syncService.syncStatePlaces("WY", false, null)

        then: "malformed places are skipped and geometry errors are recorded"
        verifyAll(placeResult) {
            created() == 0
            skipped() == 3
            failed() == 1
            errors().size() == 1
            errors()[0].startsWith("5699999:")
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid = '56'")
    }

    def "should sync layer 30 CDPs when includeCdps is true"() {
        given: "Wyoming state, empty place, and layer 30 CDP payloads"
        String emptyPlacesPayload = '{"type": "FeatureCollection", "features": []}'
        String cdpPayload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "5690010", "NAME": "Test Wyoming CDP", "STATE": "56" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-106.5, 42.2], [-106.3, 42.2], [-106.3, 42.4], [-106.5, 42.4], [-106.5, 42.2]]]
                }
            }]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            String body
            if (uri.contains("/80/query")) {
                body = WYOMING_STATE_PAYLOAD
            } else if (uri.contains("/30/query")) {
                body = cdpPayload
            } else {
                body = emptyPlacesPayload
            }
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        syncService.syncStates(null)

        when: "syncStatePlaces runs with includeCdps set to true"
        UsSyncResult placeResult = syncService.syncStatePlaces("WY", true, null)

        then: "the layer 30 CDP is created without errors"
        verifyAll(placeResult) {
            created() == 1
            skipped() == 0
            failed() == 0
            errors().isEmpty()
        }

        cleanup:
        executeSql("DELETE FROM administrative_regions WHERE geoid IN ('5690010', '56')")
    }

    def "should throw 404 when state region is not synced before county sync"() {
        given:
        UsBoundarySyncService syncService = buildSyncService(new StubHttpClient())

        when:
        syncService.syncStateCounties("AK", null)

        then:
        def ex = thrown(HttpStatusException)
        ex.status == HttpStatus.NOT_FOUND
    }

    def "removes duplicate slashes when the boundary server URL ends with a slash"() {
        given: "a sync service configured with a trailing slash baseUrl"
        String page1 = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": false,
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "08", "STATE": "08", "NAME": "Colorado" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-109.05, 37.0], [-102.04, 37.0], [-102.04, 41.0], [-109.05, 41.0], [-109.05, 37.0]]]
                }
            }]
        }'''
        List<String> requestedUris = []
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            requestedUris << req.uri().toString()
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(page1.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildTrailingSlashSyncService(stubClient)

        when: "syncStates runs"
        UsSyncResult result = syncService.syncStates(null)

        then: "URL paths have no double slash and Colorado is updated"
        verifyAll {
            result.stateCode() == "US"
            result.level() == 1
            result.updated() == 1
            result.skipped() == 0
            result.failed() == 0
            requestedUris.every { !it.contains("MapServer//80") }
        }
    }

    def "stops paging when a boundary response has no features even if more pages are flagged"() {
        given: "two pages where the second page has no features despite exceededTransferLimit being true"
        String page1 = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": true,
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "08", "STATE": "08", "NAME": "Colorado" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-109.05, 37.0], [-102.04, 37.0], [-102.04, 41.0], [-109.05, 41.0], [-109.05, 37.0]]]
                }
            }]
        }'''
        String page2 = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": true,
            "features": []
        }'''
        List<String> requestedUris = []
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            requestedUris << uri
            String body = uri.contains("resultOffset=0") ? page1 : page2
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)

        when: "syncStates runs across both pages"
        UsSyncResult result = syncService.syncStates(null)

        then: "pagination stops after the empty second page"
        verifyAll {
            result.stateCode() == "US"
            result.level() == 1
            result.updated() == 1
            result.skipped() == 0
            result.failed() == 0
            requestedUris.size() == 2
        }
    }

    def "paginates using nested exceededTransferLimit when top-level flag is not boolean"() {
        given: "two pages with a non-boolean top-level exceededTransferLimit and a nested properties flag"
        String page1 = '''{
            "type": "FeatureCollection",
            "exceededTransferLimit": "not-a-boolean",
            "properties": { "exceededTransferLimit": true },
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "08", "STATE": "08", "NAME": "Colorado" },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-109.05, 37.0], [-102.04, 37.0], [-102.04, 41.0], [-109.05, 41.0], [-109.05, 37.0]]]
                }
            }]
        }'''
        String page2 = '''{
            "type": "FeatureCollection",
            "properties": { "exceededTransferLimit": "not-a-boolean" },
            "features": []
        }'''
        List<String> requestedUris = []
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            String uri = req.uri().toString()
            requestedUris << uri
            String body = uri.contains("resultOffset=0") ? page1 : page2
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)

        when: "syncStates runs"
        UsSyncResult result = syncService.syncStates(null)

        then: "sync fetches the second page and stops when the nested flag is not boolean"
        verifyAll {
            result.stateCode() == "US"
            result.level() == 1
            result.updated() == 1
            result.skipped() == 0
            result.failed() == 0
            requestedUris.size() == 2
        }
    }

    def "skips features when properties is not an object"() {
        given: "a payload containing a feature with non-object properties"
        String payload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": "not-an-object",
                "geometry": null
            }]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)

        when: "syncStates runs"
        UsSyncResult result = syncService.syncStates(null)

        then: "the invalid feature is skipped"
        verifyAll(result) {
            stateCode() == "US"
            level() == 1
            updated() == 0
            skipped() == 1
            failed() == 0
        }
    }

    def "falls back to state code when GEOID and NAME are blank"() {
        given: "a feature with blank GEOID and NAME but a valid STATE code"
        String payload = '''{
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "properties": { "GEOID": "   ", "STATE": "08", "NAME": "   " },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[-109.05, 37.0], [-102.04, 37.0], [-102.04, 41.0], [-109.05, 41.0], [-109.05, 37.0]]]
                }
            }]
        }'''
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)

        when: "syncStates runs"
        UsSyncResult result = syncService.syncStates(null)

        then: "Colorado updates using the STATE fallback"
        verifyAll(result) {
            stateCode() == "US"
            level() == 1
            updated() == 1
            skipped() == 0
            failed() == 0
        }
    }

    def "should insert an audit log entry when syncStates runs with an actor user"() {
        given: "an empty FeatureCollection response and an actor user in the database"
        String emptyFeaturesPayload = '{"type": "FeatureCollection", "features": []}'
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(emptyFeaturesPayload.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService syncService = buildSyncService(stubClient)
        UUID actorId = UUID.randomUUID()
        executeSql("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", actorId, "sync-actor-${actorId}@example.com".toString())

        when: "syncStates runs with the actor user ID"
        UsSyncResult result = syncService.syncStates(actorId)

        then: "the sync completes and records an audit log with US metadata"
        String auditMeta = queryString(
                "SELECT metadata::text FROM administrative_region_audit_logs WHERE actor_id = ? ORDER BY created_at DESC LIMIT 1",
                actorId
        )
        verifyAll {
            result.stateCode() == "US"
            auditMeta != null
            auditMeta.contains("US")
        }

        cleanup:
        executeSql("DELETE FROM administrative_region_audit_logs WHERE actor_id = ?", actorId)
        executeSql("DELETE FROM users WHERE id = ?", actorId)
    }

    def "should return 500 when syncStateCounties encounters a SQLException during state lookup"() {
        given: "a JDBC connection that throws SQLException on prepareStatement"
        StubHttpClient fastClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream('{"type": "FeatureCollection", "features": []}'.getBytes(StandardCharsets.UTF_8)))
        })
        Connection errConn = Mock(Connection) {
            prepareStatement(_) >> { throw new SQLException("State lookup failed") }
        }
        JdbcOperations errJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(errConn) }
        }
        UsBoundarySyncService errSyncService = new UsBoundarySyncService(
                new BoundaryIngestionService(errJdbc, objectMapper, fastClient, "tigerweb.geo.census.gov", "/arcgis/rest/services/TIGERweb/"),
                errJdbc,
                objectMapper
        )

        when: "syncStateCounties runs"
        errSyncService.syncStateCounties("CO", null)

        then: "500 Internal Server Error is thrown"
        HttpStatusException sqlEx = thrown()
        verifyAll(sqlEx) {
            status == HttpStatus.INTERNAL_SERVER_ERROR
            message.contains("Failed looking up state region")
        }
    }

    def "should fall back to empty JSON metadata when ObjectMapper throws IOException during audit logging"() {
        given: "an ObjectMapper that fails to serialize audit metadata"
        String emptyFeaturesPayload = '{"type": "FeatureCollection", "features": []}'
        StubHttpClient fastClient = new StubHttpClient(sendHandler: { req, handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(emptyFeaturesPayload.getBytes(StandardCharsets.UTF_8)))
        })
        PreparedStatement auditStmt = Mock(PreparedStatement) {
            executeUpdate() >> 1
        }
        ObjectMapper failingMapper = Mock(ObjectMapper) {
            readValue(_ as byte[], _ as Class) >> objectMapper.readValue(emptyFeaturesPayload.getBytes(StandardCharsets.UTF_8), JsonNode)
            writeValueAsString(_) >> { throw new IOException("Audit JSON failed") }
        }
        Connection okConn = Mock(Connection) {
            prepareStatement(_) >> auditStmt
        }
        JdbcOperations okJdbc = Mock(JdbcOperations) {
            execute(_) >> { args -> args[0].call(okConn) }
        }
        UsBoundarySyncService fallbackSyncService = new UsBoundarySyncService(
                new BoundaryIngestionService(okJdbc, failingMapper, fastClient, "tigerweb.geo.census.gov", "/arcgis/rest/services/TIGERweb/"),
                okJdbc,
                failingMapper
        )

        when: "syncStates runs with an actor user ID"
        UsSyncResult fallbackResult = fallbackSyncService.syncStates(UUID.randomUUID())

        then: "audit log falls back to {} and sync succeeds"
        1 * auditStmt.setString(3, "{}")
        fallbackResult.stateCode() == "US"
    }

    def "returns service unavailable when a waiting sync call is interrupted"() {
        given: "an active syncStates call holding the sync lock"
        String emptyFeaturesPayload = '{"type": "FeatureCollection", "features": []}'
        CountDownLatch startedLatch = new CountDownLatch(1)
        CountDownLatch releaseLatch = new CountDownLatch(1)
        StubHttpClient blockingClient = new StubHttpClient(sendHandler: { req, handler ->
            startedLatch.countDown()
            releaseLatch.await(30, TimeUnit.SECONDS)
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(emptyFeaturesPayload.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService contendedService = buildSyncService(blockingClient)
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        Future<UsSyncResult> holderFuture = null
        HttpStatusException interruptedEx = null

        when: "a second caller is interrupted while waiting for the lock"
        try {
            holderFuture = executor.submit({ -> contendedService.syncStates(null) })
            assert startedLatch.await(5, TimeUnit.SECONDS)

            Future<HttpStatusException> interruptedFuture = executor.submit({ ->
                Thread.currentThread().interrupt()
                try {
                    contendedService.syncStates(null)
                    return null
                } catch (HttpStatusException ex) {
                    return ex
                }
            })
            interruptedEx = interruptedFuture.get(5, TimeUnit.SECONDS)
        } finally {
            releaseLatch.countDown()
            try {
                holderFuture?.get(5, TimeUnit.SECONDS)
            } finally {
                executor.close()
            }
        }

        then: "interrupted caller receives 503 Service Unavailable"
        verifyAll {
            interruptedEx != null
            interruptedEx.status == HttpStatus.SERVICE_UNAVAILABLE
        }
    }

    def "returns too many requests when a sync call times out waiting for an active sync"() {
        given: "an active syncStates call holding the sync lock"
        String emptyFeaturesPayload = '{"type": "FeatureCollection", "features": []}'
        CountDownLatch startedLatch = new CountDownLatch(1)
        CountDownLatch releaseLatch = new CountDownLatch(1)
        StubHttpClient blockingClient = new StubHttpClient(sendHandler: { req, handler ->
            startedLatch.countDown()
            releaseLatch.await(30, TimeUnit.SECONDS)
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(emptyFeaturesPayload.getBytes(StandardCharsets.UTF_8)))
        })
        UsBoundarySyncService contendedService = buildSyncService(blockingClient)
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        Future<UsSyncResult> holderFuture = null
        HttpStatusException busyEx = null

        when: "another caller times out waiting for the lock"
        try {
            holderFuture = executor.submit({ -> contendedService.syncStates(null) })
            assert startedLatch.await(5, TimeUnit.SECONDS)

            try {
                contendedService.syncStates(null)
            } catch (HttpStatusException ex) {
                busyEx = ex
            }
        } finally {
            releaseLatch.countDown()
            try {
                holderFuture?.get(5, TimeUnit.SECONDS)
            } finally {
                executor.close()
            }
        }

        then: "timed-out caller receives 429 Too Many Requests"
        verifyAll {
            busyEx != null
            busyEx.status == HttpStatus.TOO_MANY_REQUESTS
        }
    }
}
