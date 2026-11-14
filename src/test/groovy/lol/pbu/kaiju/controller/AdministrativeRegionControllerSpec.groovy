package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.dto.CoordinateDto
import lol.pbu.kaiju.dto.CreateAdministrativeRegionCommand
import lol.pbu.kaiju.dto.UpdateAdministrativeRegionCommand
import lol.pbu.kaiju.dto.UsSyncResult
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.service.UsBoundarySyncService
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Unroll

import java.util.UUID

@MicronautTest(transactional = false)
class AdministrativeRegionControllerSpec extends BaseControllerSpec {

    @Inject
    AdministrativeRegionRepository administrativeRegionRepository

    @Shared
    StubUsBoundarySyncService syncStub = new StubUsBoundarySyncService()

    @MockBean(UsBoundarySyncService)
    UsBoundarySyncService usBoundarySyncServiceMock() {
        return syncStub
    }

    static class StubUsBoundarySyncService extends UsBoundarySyncService {
        Closure<UsSyncResult> syncStatesHandler
        Closure<UsSyncResult> syncCountiesHandler
        Closure<UsSyncResult> syncPlacesHandler
        int callCount = 0

        StubUsBoundarySyncService() {
            super(null, null, null)
        }

        @Override
        UsSyncResult syncStates(UUID actorUserId) {
            callCount++
            return syncStatesHandler.call(actorUserId)
        }

        @Override
        UsSyncResult syncStateCounties(String stateCode, UUID actorUserId) {
            callCount++
            return syncCountiesHandler.call(stateCode, actorUserId)
        }

        @Override
        UsSyncResult syncStatePlaces(String stateCode, boolean includeCdps, UUID actorUserId) {
            callCount++
            return syncPlacesHandler.call(stateCode, includeCdps, actorUserId)
        }
    }

    @Shared
    Faker faker = new Faker()

    @Shared
    GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)

    private Geometry createTestPolygon() {
        Coordinate[] coords = [
                new Coordinate(-105.0, 39.0),
                new Coordinate(-104.0, 39.0),
                new Coordinate(-104.0, 40.0),
                new Coordinate(-105.0, 40.0),
                new Coordinate(-105.0, 39.0)
        ]
        return geometryFactory.createPolygon(coords)
    }

    private List<CoordinateDto> createCoordinateDtos() {
        [
                new CoordinateDto(-105.0, 39.0),
                new CoordinateDto(-104.0, 39.0),
                new CoordinateDto(-104.0, 40.0),
                new CoordinateDto(-105.0, 40.0),
                new CoordinateDto(-105.0, 39.0)
        ]
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid administrative region"() {
        given: "a new valid administrative region command"
        String regionName = "Region ${faker.address().state()}"
        def command = new CreateAdministrativeRegionCommand(regionName, null, createCoordinateDtos())

        when: "the administrative region is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions", command)), AdministrativeRegion)
        AdministrativeRegion saved = track(response.body())

        then: "the administrative region is persisted with 200 OK and generated ID"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id() != null
            saved.name() == regionName
            saved.geom() != null
        }

        and: "it can be retrieved from the database"
        def result = administrativeRegionRepository.findById(saved.id()).orElse(null)
        verifyAll(result) {
            result != null
            id() == saved.id()
            name() == saved.name()
        }
    }

    @Unroll
    def "CREATE | should fail to save administrative region with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add an administrative region with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions", payload)), AdministrativeRegion)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase                    | payload
        "Null Name"                 | [name: null, coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0]]]
        "Blank Name"                | [name: "   ", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0]]]
        "Name Too Long"             | [name: "A" * 256, coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0]]]
        "Null Coordinates"          | [name: "Valid Region", coordinates: null]
        "Less Than 3 Coordinates"   | [name: "Valid Region", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0]]]
        "Longitude Out of Bounds"   | [name: "Valid Region", coordinates: [[longitude: 181.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0]]]
        "Latitude Out of Bounds"    | [name: "Valid Region", coordinates: [[longitude: -105.0, latitude: -91.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0]]]
    }

    def "CREATE | should reject unauthenticated POST /administrative-regions with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to create an administrative region"
        client.exchange(HttpRequest.POST("/administrative-regions", [
                name       : "Unauth Region",
                coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0]]
        ]), AdministrativeRegion)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing administrative region by ID"() {
        given: "an existing administrative region"
        def geom = createTestPolygon()
        def region = dataBuilder.region().withName("Test Region Read").withGeometry(geom).persist()
        UUID id = region.id()

        when: "the administrative region is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/administrative-regions/${id}")), AdministrativeRegion)

        then: "200 OK is returned with the correct administrative region"
        verifyAll(response) {
            status == HttpStatus.OK
            body().id() == id
            body().name() == "Test Region Read"
        }
    }

    def "READ | should return 404 for a non-existent administrative region ID"() {
        when: "a non-existent administrative region is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/administrative-regions/${UUID.randomUUID()}")), AdministrativeRegion)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /administrative-regions/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to get an administrative region"
        client.exchange(HttpRequest.GET("/administrative-regions/${UUID.randomUUID()}"), AdministrativeRegion)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing administrative region"() {
        given: "an existing administrative region"
        def geom = createTestPolygon()
        def region = dataBuilder.region().withName("Original Region Name").withGeometry(geom).persist()
        UUID id = region.id()
        def newName = "Updated Region ${faker.address().state()}"
        def updateCommand = new UpdateAdministrativeRegionCommand(newName, null, createCoordinateDtos())

        when: "the administrative region is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/administrative-regions/${id}", updateCommand)), AdministrativeRegion)
        AdministrativeRegion updated = response.body()

        then: "the returned administrative region contains the updated data with 200 OK"
        verifyAll {
            response.status == HttpStatus.OK
            updated.id() == id
            updated.name() == newName
        }

        and: "the changes are persisted in the database"
        def dbResult = administrativeRegionRepository.findById(id).orElse(null)
        verifyAll(dbResult) {
            dbResult != null
            name() == newName
        }
    }

    def "UPDATE | should fail to update a non-existent administrative region"() {
        given: "a random non-existent ID and an update command"
        def nonExistentId = UUID.randomUUID()
        def updateCommand = new UpdateAdministrativeRegionCommand("Test Region", null, createCoordinateDtos())

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/administrative-regions/${nonExistentId}", updateCommand)), AdministrativeRegion)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "UPDATE | should fail to update administrative region with invalid data: #testCase"(String testCase, Map payload) {
        given: "an existing administrative region"
        def region = dataBuilder.region().withName("Test Update Invalid").withGeometry(createTestPolygon()).persist()
        UUID id = region.id()

        when: "an update with invalid data is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/administrative-regions/${id}", payload)), AdministrativeRegion)

        then: "a 400 BAD REQUEST status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase                  | payload
        "Blank Name"              | [name: " ", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0]]]
        "Less Than 3 Coordinates" | [name: "Valid", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0]]]
    }

    def "UPDATE | should reject unauthenticated PUT /administrative-regions/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to update an administrative region"
        client.exchange(HttpRequest.PUT("/administrative-regions/${UUID.randomUUID()}", [
                name       : "Unauth Region",
                coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0]]
        ]), AdministrativeRegion)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing administrative region"() {
        given: "a new administrative region to be deleted"
        def saved = dataBuilder.region().withName("Temporary Region to Delete").withGeometry(createTestPolygon()).persist()
        UUID id = saved.id()
        assert administrativeRegionRepository.existsById(id)

        when: "the administrative region is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/administrative-regions/${id}")))

        then: "the response is 200 OK and region no longer exists in repository or database"
        verifyAll {
            response.status == HttpStatus.OK
            !administrativeRegionRepository.findById(id).isPresent()
            !administrativeRegionRepository.existsById(id)
        }
    }

    def "DELETE | should fail to delete a non-existent administrative region"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/administrative-regions/${nonExistentId}")))

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "DELETE | should reject unauthenticated DELETE /administrative-regions/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to delete an administrative region"
        client.exchange(HttpRequest.DELETE("/administrative-regions/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve administrative regions with pagination"() {
        given: "persisted administrative regions"
        dataBuilder.region().withName("Test Region Paging 1").withGeometry(createTestPolygon()).persist()
        dataBuilder.region().withName("Test Region Paging 2").withGeometry(createTestPolygon()).persist()

        when: "requesting administrative regions via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/administrative-regions?size=5")), Map)

        then: "the response is 200 OK with content list"
        Map body = response.body()
        verifyAll {
            response.status == HttpStatus.OK
            body.content instanceof List
            body.content.size() >= 2
        }
    }

    def "LIST | should reject unauthenticated GET /administrative-regions with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to list administrative regions"
        client.exchange(HttpRequest.GET("/administrative-regions"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** US SYNC Tests **********/

    def "SYNC | global admin can sync US states"() {
        given: "a stubbed sync result"
        def expected = new UsSyncResult("US", 1, 50, 6, 0, 0, [])
        UUID capturedActor = null
        syncStub.syncStatesHandler = { UUID actorId ->
            capturedActor = actorId
            return expected
        }

        when: "a global admin triggers US state sync"
        def response = client.exchange(
                asGlobalAdmin(HttpRequest.POST("/administrative-regions/sync/us/states", "")),
                UsSyncResult
        )

        then: "the sync service is called and 200 OK is returned"
        verifyAll(response) {
            status == HttpStatus.OK
            body() == expected
            capturedActor != null
        }
    }

    def "SYNC | global admin can sync US counties for a state"() {
        given: "a stubbed county sync result"
        def expected = new UsSyncResult("CO", 2, 64, 0, 0, 0, [])
        String capturedState = null
        syncStub.syncCountiesHandler = { String stateCode, UUID actorId ->
            capturedState = stateCode
            return expected
        }

        when: "a global admin triggers county sync for CO"
        def response = client.exchange(
                asGlobalAdmin(HttpRequest.POST("/administrative-regions/sync/us/states/CO/counties", "")),
                UsSyncResult
        )

        then: "the sync service is called and 200 OK is returned"
        verifyAll(response) {
            status == HttpStatus.OK
            body() == expected
            capturedState == "CO"
        }
    }

    def "SYNC | global admin can sync US places with and without includeCdps"() {
        given: "stubbed place sync results"
        def defaultResult = new UsSyncResult("CO", 3, 270, 0, 0, 0, [])
        def cdpResult = new UsSyncResult("CO", 3, 270, 200, 0, 0, [])
        Boolean capturedIncludeCdps = null
        syncStub.syncPlacesHandler = { String stateCode, boolean includeCdps, UUID actorId ->
            capturedIncludeCdps = includeCdps
            return includeCdps ? cdpResult : defaultResult
        }

        when: "a global admin triggers place sync without includeCdps"
        def defaultResponse = client.exchange(
                asGlobalAdmin(HttpRequest.POST("/administrative-regions/sync/us/states/CO/places", "")),
                UsSyncResult
        )

        then: "includeCdps defaults to false"
        verifyAll(defaultResponse) {
            status == HttpStatus.OK
            body() == defaultResult
            capturedIncludeCdps == false
        }

        when: "a global admin triggers place sync with includeCdps=true"
        def cdpResponse = client.exchange(
                asGlobalAdmin(HttpRequest.POST("/administrative-regions/sync/us/states/CO/places?includeCdps=true", "")),
                UsSyncResult
        )

        then: "includeCdps is passed as true"
        verifyAll(cdpResponse) {
            status == HttpStatus.OK
            body() == cdpResult
            capturedIncludeCdps == true
        }
    }

    def "SYNC | non-admin cannot trigger US boundary sync"() {
        given: "a reset stub call counter"
        syncStub.callCount = 0

        when: "a standard user attempts to trigger US state sync"
        client.exchange(
                asStandardUser(HttpRequest.POST("/administrative-regions/sync/us/states", "")),
                UsSyncResult
        )

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        verifyAll {
            e.status == HttpStatus.FORBIDDEN
            syncStub.callCount == 0
        }
    }
}
