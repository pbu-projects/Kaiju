package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.dto.CreateLocationCommand
import lol.pbu.kaiju.dto.UpdateLocationCommand
import lol.pbu.kaiju.repository.LocationRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Unroll

import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class LocationControllerSpec extends BaseControllerSpec {

    private static final String ROLE_STANDARD_USER = "STANDARD_USER"
    private static final String ROLE_REGION_AGENT = "REGION_AGENT"
    private static final String ROLE_REGION_DIRECTOR = "REGION_DIRECTOR"
    private static final String CLAIM_REGION_MANAGE = "region:manage"
    private static final String BASE_PATH = "/locations"

    @Inject
    LocationRepository locationRepository

    @Shared
    Faker faker = new Faker()

    @Shared
    GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)

    protected <T> MutableHttpRequest<T> asStandardUser(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, [ROLE_STANDARD_USER])
    }

    protected <T> MutableHttpRequest<T> asRegionAgent(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, [ROLE_REGION_AGENT])
    }

    protected <T> MutableHttpRequest<T> asRegionDirector(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, [ROLE_REGION_DIRECTOR, CLAIM_REGION_MANAGE])
    }

    private Point createPoint(double lon = 0, double lat = 0) {
        geometryFactory.createPoint(new Coordinate(lon, lat))
    }

    def cleanup() {
        sql.execute("DELETE FROM locations WHERE name LIKE 'Test%' OR name LIKE 'Updated%' OR name LIKE 'Original%' OR name LIKE 'Director%'")
    }

    /********** CREATE Tests **********/

    @Unroll
    def "CREATE | should successfully save a valid location: #country"(String country, String address, String city, String stateProvince, String postalCode, String countryCode, double lon, double lat) {
        given: "a new valid location command"
        String name = "Test ${faker.company().name()}"
        def command = new CreateLocationCommand(name, address, city, stateProvince, postalCode, countryCode, lon, lat)

        when: "the location is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/locations", command)), Location)
        Location saved = response.body()

        then: "the location is persisted with 200 OK and generated ID"
        response.status == HttpStatus.OK
        verifyAll {
            saved.id() != null
            saved.name() == name
            saved.countryCode() == countryCode
            saved.geom() != null
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM locations WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == result.name
        }

        where:
        country     | address         | city      | stateProvince | postalCode | countryCode | lon      | lat
        "UK"        | "10 Downing St" | "London"  | null          | "SW1A 2AA" | "GB"        | -0.1276  | 51.5072
        "Canada"    | "1 Front St W"  | "Toronto" | "ON"          | "M5J 2X5"  | "CA"        | -79.3786 | 43.6465
        "Japan"     | "千代田区1-1"   | "東京都"  | null          | "100-0001" | "JP"        | 139.7528 | 35.6852
        "Australia" | "Sydney Opera"  | "Sydney"  | "NSW"         | "2000"     | "AU"        | 151.2153 | -33.8568
    }

    @Unroll
    def "CREATE | should fail to save location with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add a location with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/locations", payload)), Location)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        [testCase, payload] << {
            def validData = [
                    name         : "Valid Name",
                    addressLine  : "Valid Address",
                    city         : "Valid City",
                    stateProvince: "ST",
                    postalCode   : "12345",
                    countryCode  : "US",
                    longitude    : -104.99,
                    latitude     : 39.74
            ]

            def invalidCases = [
                    [field: 'name', value: null, caseName: "Null Name"],
                    [field: 'name', value: ' ', caseName: "Blank Name"],
                    [field: 'addressLine', value: null, caseName: "Null Address"],
                    [field: 'city', value: null, caseName: "Null City"],
                    [field: 'countryCode', value: null, caseName: "Null Country"],
                    [field: 'countryCode', value: 'U', caseName: "Short Country"],
                    [field: 'countryCode', value: 'USA', caseName: "Long Country"],
                    [field: 'longitude', value: null, caseName: "Null Longitude"],
                    [field: 'longitude', value: 181.0, caseName: "Longitude Out of Bounds Max"],
                    [field: 'longitude', value: -181.0, caseName: "Longitude Out of Bounds Min"],
                    [field: 'latitude', value: null, caseName: "Null Latitude"],
                    [field: 'latitude', value: 91.0, caseName: "Latitude Out of Bounds Max"],
                    [field: 'latitude', value: -91.0, caseName: "Latitude Out of Bounds Min"]
            ]

            return invalidCases.collect { invalidCase ->
                def props = new HashMap(validData)
                props[invalidCase.field] = invalidCase.value
                [invalidCase.caseName, props]
            }
        }()
    }

    def "CREATE | should reject unauthenticated POST /locations with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to create a location"
        client.exchange(HttpRequest.POST("/locations", [
                name       : "Unauth Location",
                addressLine: "123 Street",
                city       : "City",
                countryCode: "US",
                longitude  : -104.99,
                latitude   : 39.74
        ]), Location)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "CREATE | should allow regional director with region:manage claim to create a location"() {
        given: "a new valid location command"
        String name = "Director Location ${faker.company().name()}"
        def command = new CreateLocationCommand(name, "123 Main St", "Denver", "CO", "80202", "US", -104.99, 39.74)

        when: "the location is added by a regional director via HTTP POST"
        def response = client.exchange(asRegionDirector(HttpRequest.POST(BASE_PATH, command)), Location)
        Location saved = response.body()

        then: "the location is persisted with 200 OK and generated ID"
        response.status == HttpStatus.OK
        verifyAll {
            saved.id() != null
            saved.name() == name
            saved.countryCode() == "US"
            saved.geom() != null
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM locations WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == result.name
        }

        cleanup:
        if (saved?.id()) {
            sql.execute("DELETE FROM locations WHERE id = ?", [saved.id()])
        }
    }

    def "CREATE | should reject standard user attempting POST /locations with 403 FORBIDDEN"() {
        given: "a valid location command"
        def command = new CreateLocationCommand("Standard User Location", "123 Main St", "Denver", "CO", "80202", "US", -104.99, 39.74)

        when: "a standard user attempts to create a location via HTTP POST"
        client.exchange(asStandardUser(HttpRequest.POST(BASE_PATH, command)), Location)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "CREATE | should reject regional agent without region:manage claim attempting POST /locations with 403 FORBIDDEN"() {
        given: "a valid location command"
        def command = new CreateLocationCommand("Agent Location", "123 Main St", "Denver", "CO", "80202", "US", -104.99, 39.74)

        when: "a regional agent attempts to create a location via HTTP POST"
        client.exchange(asRegionAgent(HttpRequest.POST(BASE_PATH, command)), Location)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing location by ID"() {
        given: "an existing location"
        def location = locationRepository.save(new Location(null, "Test Location Read", "123 Main St", "City", "UT", "84000", "US", createPoint()))
        UUID id = location.id()

        when: "the location is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/locations/${id}")), Location)

        then: "the correct location is returned with 200 OK"
        response.status == HttpStatus.OK
        verifyAll {
            response.body().id() == id
            response.body().name() == "Test Location Read"
        }
    }

    def "READ | should return 404 for a non-existent location ID"() {
        when: "a non-existent location is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/locations/${UUID.randomUUID()}")), Location)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /locations/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to get a location"
        client.exchange(HttpRequest.GET("/locations/${UUID.randomUUID()}"), Location)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing location"() {
        given: "an existing location to update"
        def location = locationRepository.save(new Location(null, "Original Location Name", "123 Main St", "Original City", "UT", "84000", "US", createPoint()))
        UUID id = location.id()
        def newName = "Updated ${faker.commerce().productName()}"
        def newCity = faker.address().city()
        def updateCommand = new UpdateLocationCommand(newName, "Updated Address", newCity, "UT", "84000", "US", -105.0, 40.0)

        when: "the location is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/locations/${id}", updateCommand)), Location)
        Location updated = response.body()

        then: "the returned location contains the updated data with 200 OK"
        response.status == HttpStatus.OK
        verifyAll {
            updated.id() == id
            updated.name() == newName
            updated.city() == newCity
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT name, city FROM locations WHERE id = ?", [id])
        verifyAll(dbResult) {
            name == newName
            city == newCity
        }
    }

    def "UPDATE | should fail to update a non-existent location"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()
        def updateCommand = new UpdateLocationCommand("Test", "Addr", "City", "ST", "12345", "US", 0.0, 0.0)

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/locations/${nonExistentId}", updateCommand)), Location)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "UPDATE | should fail to update location with invalid data: #testCase"(String testCase, Map payload) {
        given: "an existing location"
        def location = locationRepository.save(new Location(null, "Test Update Invalid", "123 St", "City", "UT", "84000", "US", createPoint()))
        UUID id = location.id()

        when: "an update with invalid data is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/locations/${id}", payload)), Location)

        then: "a 400 BAD REQUEST status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase        | payload
        "Blank Name"    | [name: " ", addressLine: "A", city: "C", countryCode: "US", longitude: 0.0, latitude: 0.0]
        "Short Country" | [name: "N", addressLine: "A", city: "C", countryCode: "U", longitude: 0.0, latitude: 0.0]
    }

    def "UPDATE | should reject unauthenticated PUT /locations/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to update a location"
        client.exchange(HttpRequest.PUT("/locations/${UUID.randomUUID()}", [
                name       : "Unauth Location",
                addressLine: "123 Street",
                city       : "City",
                countryCode: "US",
                longitude  : -104.99,
                latitude   : 39.74
        ]), Location)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "UPDATE | should allow regional director with region:manage claim to update an existing location"() {
        given: "an existing location"
        def location = locationRepository.save(new Location(null, "Original Location Director", "123 Main St", "Original City", "CO", "80202", "US", createPoint()))
        UUID id = location.id()
        def newName = "Director Location Update ${faker.company().name()}"
        def updateCommand = new UpdateLocationCommand(newName, "456 Update Ave", "Denver", "CO", "80202", "US", -104.99, 39.74)

        when: "the location is updated by a regional director via HTTP PUT"
        def response = client.exchange(asRegionDirector(HttpRequest.PUT("${BASE_PATH}/${id}", updateCommand)), Location)
        Location updated = response.body()

        then: "the returned location contains the updated data with 200 OK"
        response.status == HttpStatus.OK
        verifyAll {
            updated.id() == id
            updated.name() == newName
            updated.city() == "Denver"
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT name, city FROM locations WHERE id = ?", [id])
        verifyAll(dbResult) {
            name == newName
            city == "Denver"
        }

        cleanup:
        sql.execute("DELETE FROM locations WHERE id = ?", [id])
    }

    def "UPDATE | should reject standard user attempting PUT /locations/{id} with 403 FORBIDDEN"() {
        given: "an update command and target location ID"
        def updateCommand = new UpdateLocationCommand("Unauthorized Update", "123 Main St", "Denver", "CO", "80202", "US", -104.99, 39.74)
        UUID id = UUID.randomUUID()

        when: "a standard user attempts to update a location via HTTP PUT"
        client.exchange(asStandardUser(HttpRequest.PUT("${BASE_PATH}/${id}", updateCommand)), Location)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should reject regional agent without region:manage claim attempting PUT /locations/{id} with 403 FORBIDDEN"() {
        given: "an update command and target location ID"
        def updateCommand = new UpdateLocationCommand("Unauthorized Update", "123 Main St", "Denver", "CO", "80202", "US", -104.99, 39.74)
        UUID id = UUID.randomUUID()

        when: "a regional agent attempts to update a location via HTTP PUT"
        client.exchange(asRegionAgent(HttpRequest.PUT("${BASE_PATH}/${id}", updateCommand)), Location)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing location"() {
        given: "a new location to be deleted"
        def tempLoc = new Location(null, "Test Delete Location", "123 Delete St", "Delete City", "UT", "00000", "US", createPoint())
        def saved = locationRepository.save(tempLoc)
        UUID id = saved.id()
        assert locationRepository.existsById(id)

        when: "the location is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/locations/${id}")))

        then: "the response is 200 OK"
        response.status == HttpStatus.OK

        and: "the location no longer exists in the repository or database"
        verifyAll {
            !locationRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM locations WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent location"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/locations/${nonExistentId}")))

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "DELETE | should reject unauthenticated DELETE /locations/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to delete a location"
        client.exchange(HttpRequest.DELETE("/locations/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "DELETE | should allow regional director with region:manage claim to remove an existing location"() {
        given: "a new location to be deleted"
        def tempLoc = new Location(null, "Director Delete Location", "123 Delete St", "Denver", "CO", "80202", "US", createPoint())
        def saved = locationRepository.save(tempLoc)
        UUID id = saved.id()
        assert locationRepository.existsById(id)

        when: "the location is deleted by a regional director via HTTP DELETE"
        def response = client.exchange(asRegionDirector(HttpRequest.DELETE("${BASE_PATH}/${id}")))

        then: "the response is 200 OK"
        response.status == HttpStatus.OK

        and: "the location no longer exists in the repository or database"
        verifyAll {
            !locationRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM locations WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should reject standard user attempting DELETE /locations/{id} with 403 FORBIDDEN"() {
        when: "a standard user attempts to delete a location via HTTP DELETE"
        client.exchange(asStandardUser(HttpRequest.DELETE("${BASE_PATH}/${UUID.randomUUID()}")))

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should reject regional agent without region:manage claim attempting DELETE /locations/{id} with 403 FORBIDDEN"() {
        when: "a regional agent attempts to delete a location via HTTP DELETE"
        client.exchange(asRegionAgent(HttpRequest.DELETE("${BASE_PATH}/${UUID.randomUUID()}")))

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve locations with pagination"() {
        given: "two persisted locations"
        locationRepository.save(new Location(null, "Test Paging Loc 1", "123 St", "City", "UT", "84000", "US", createPoint()))
        locationRepository.save(new Location(null, "Test Paging Loc 2", "456 St", "City", "UT", "84000", "US", createPoint()))

        when: "requesting locations via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/locations?size=5")), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
        body.content.size() >= 2
    }

    def "LIST | should reject unauthenticated GET /locations with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to list locations"
        client.exchange(HttpRequest.GET("/locations"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }
}
