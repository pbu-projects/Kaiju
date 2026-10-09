package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.dto.CoordinateDto
import lol.pbu.kaiju.dto.CreateAdministrativeRegionCommand
import lol.pbu.kaiju.dto.UpdateAdministrativeRegionCommand
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Unroll

import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class AdministrativeRegionControllerSpec extends BaseControllerSpec {

    @Inject
    AdministrativeRegionRepository administrativeRegionRepository

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

    def cleanup() {
        sql.execute("DELETE FROM administrative_regions WHERE name LIKE 'Region %' OR name LIKE 'Test Region %' OR name LIKE 'Original Region %' OR name LIKE 'Updated Region %' OR name LIKE 'Temporary Region %'")
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid administrative region"() {
        given: "a new valid administrative region command"
        String regionName = "Region ${faker.address().state()}"
        def command = new CreateAdministrativeRegionCommand(regionName, null, createCoordinateDtos())

        when: "the administrative region is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-regions", command)), AdministrativeRegion)
        AdministrativeRegion saved = response.body()

        then: "the administrative region is persisted with 200 OK and generated ID"
        response.status == HttpStatus.OK
        verifyAll {
            saved.id() != null
            saved.name() == regionName
            saved.geom() != null
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM administrative_regions WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == name
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
        def region = administrativeRegionRepository.save(new AdministrativeRegion(null, "Test Region Read", null, geom))
        UUID id = region.id()

        when: "the administrative region is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/administrative-regions/${id}")), AdministrativeRegion)

        then: "200 OK is returned with the correct administrative region"
        response.status == HttpStatus.OK
        verifyAll {
            response.body().id() == id
            response.body().name() == "Test Region Read"
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
        def region = administrativeRegionRepository.save(new AdministrativeRegion(null, "Original Region Name", null, geom))
        UUID id = region.id()
        def newName = "Updated Region ${faker.address().state()}"
        def updateCommand = new UpdateAdministrativeRegionCommand(newName, null, createCoordinateDtos())

        when: "the administrative region is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/administrative-regions/${id}", updateCommand)), AdministrativeRegion)
        AdministrativeRegion updated = response.body()

        then: "the returned administrative region contains the updated data with 200 OK"
        response.status == HttpStatus.OK
        verifyAll {
            updated.id() == id
            updated.name() == newName
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT name FROM administrative_regions WHERE id = ?", [id])
        verifyAll(dbResult) {
            name == newName
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
        def region = administrativeRegionRepository.save(new AdministrativeRegion(null, "Test Update Invalid", null, createTestPolygon()))
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
        def geom = createTestPolygon()
        def tempRegion = new AdministrativeRegion(null, "Temporary Region to Delete", null, geom)
        def saved = administrativeRegionRepository.save(tempRegion)
        UUID id = saved.id()
        assert administrativeRegionRepository.existsById(id)

        when: "the administrative region is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/administrative-regions/${id}")))

        then: "the response is 200 OK"
        response.status == HttpStatus.OK

        and: "the administrative region no longer exists in the repository or database"
        verifyAll {
            !administrativeRegionRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM administrative_regions WHERE id = ?", [id]).count == 0
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
        administrativeRegionRepository.save(new AdministrativeRegion(null, "Test Region Paging 1", null, createTestPolygon()))
        administrativeRegionRepository.save(new AdministrativeRegion(null, "Test Region Paging 2", null, createTestPolygon()))

        when: "requesting administrative regions via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/administrative-regions?size=5")), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
        body.content.size() >= 2
    }

    def "LIST | should reject unauthenticated GET /administrative-regions with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to list administrative regions"
        client.exchange(HttpRequest.GET("/administrative-regions"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }
}
