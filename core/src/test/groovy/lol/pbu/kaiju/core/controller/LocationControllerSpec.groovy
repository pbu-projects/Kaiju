package lol.pbu.kaiju.core.controller

import groovy.sql.Sql
import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.core.domain.Location
import lol.pbu.kaiju.core.repository.LocationRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.sql.Connection
/**
 * These are more for my own comfort than anything.
 */
@MicronautTest
class LocationControllerSpec extends Specification {

    @Inject @Shared
    Connection connection

    @Shared
    Sql sql

    @Inject
    LocationRepository locationRepository

    @Inject
    LocationController locationController

    @Shared
    Faker faker = new Faker()

    @Shared
    GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)

    private Point createPoint(double lon = 0, double lat = 0) {
        geometryFactory.createPoint(new Coordinate(lon, lat))
    }

    def setupSpec() {
        // look in database/compose.yml
        sql = Sql.newInstance("jdbc:postgresql://localhost:5432/volunteer_monster", "jimmy", "warm-farts-smell-worse")
    }

    def cleanupSpec() {
        sql?.close()
    }

    /********** CREATE Tests **********/

    @Unroll
    def "CREATE | should successfully save a valid location: #country"(String country, String address, String city, String stateProvince, String postalCode, String countryCode, double lon, double lat) {
        given: "a new valid location"
        def newLocation = new Location(null, "Test ${faker.company().name()}", address, city, stateProvince, postalCode, countryCode, createPoint(lon, lat))

        when: "the location is added"
        Location saved = locationController.addLocation(newLocation)

        then: "the location is persisted with a generated ID"
        verifyAll {
            saved.id() != null
            saved.name() == newLocation.name()
            saved.countryCode() == countryCode
        }

        and: "it can be retrieved from the database"
        def result = new Sql(connection).firstRow("SELECT * FROM locations WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == name
        }

        where:
        country     | address         | city      | stateProvince | postalCode | countryCode | lon      | lat
        "UK"        | "10 Downing St" | "London"  | null          | "SW1A 2AA" | "GB"        | -0.1276  | 51.5072
        "Canada"    | "1 Front St W"  | "Toronto" | "ON"          | "M5J 2X5"  | "CA"        | -79.3786 | 43.6465
        "Japan"     | "千代田区1-1"   | "東京都"  | null          | "100-0001" | "JP"        | 139.7528 | 35.6852
        "Australia" | "Sydney Opera"  | "Sydney"  | "NSW"         | "2000"     | "AU"        | 151.2153 | -33.8568
    }

    @Unroll
    def "CREATE | should fail to save location with invalid data: #testCase"(String testCase, Location location) {
        when: "an attempt is made to add a location with invalid data"
        locationController.addLocation(location)

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, location] << {
            def validData = [
                    name         : "Valid Name",
                    addressLine  : "Valid Address",
                    city         : "Valid City",
                    stateProvince: "ST",
                    postalCode   : "12345",
                    countryCode  : "US"
            ]

            def invalidCases = [
                    [field: 'name', value: null, caseName: "Null Name"],
                    [field: 'name', value: ' ', caseName: "Blank Name"],
                    [field: 'addressLine', value: null, caseName: "Null Address"],
                    [field: 'city', value: null, caseName: "Null City"],
                    [field: 'countryCode', value: null, caseName: "Null Country"],
                    [field: 'countryCode', value: 'U', caseName: "Short Country"],
                    [field: 'countryCode', value: 'USA', caseName: "Long Country"]
            ]

            return invalidCases.collect { invalidCase ->
                def props = new HashMap(validData)
                props[invalidCase.field] = invalidCase.value
                def loc = new Location(
                        null,
                        props.name as String,
                        props.addressLine as String,
                        props.city as String,
                        props.stateProvince as String,
                        props.postalCode as String,
                        props.countryCode as String,
                        createPoint()
                )
                [invalidCase.caseName, loc]
            }
        }()
    }

    /********** READ Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "READ | should retrieve an existing location by ID: #name"(UUID id, String name) {
        when: "the location is requested by its ID"
        def result = locationController.getLocation(id)

        then: "the correct location is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
            result.get().name() == name
        }

        where:
        [id, name] << sql.rows("SELECT id, name FROM locations LIMIT 3").collect { [it.id, it.name] }
    }

    @Unroll
    def "READ | should return empty for a non-existent location ID"() {
        when: "a non-existent location is requested"
        def result = locationController.getLocation(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck") // where block left shift
    def "UPDATE | should successfully update an existing location: #originalName"(UUID id, String originalName) {
        given: "an existing location to update"
        def sql = new Sql(connection)
        def newName = "Updated ${faker.commerce().productName()}"
        def newCity = faker.address().city()
        def updateRequest = new Location(null, newName, "Updated Address", newCity, "UT", "84000", "US", createPoint())

        when: "the location is updated"
        Location updated = locationController.updateLocation(id, updateRequest)

        then: "the returned location contains the updated data"
        verifyAll() {
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

        where:
        [id, originalName] << sql.rows("SELECT id, name FROM locations LIMIT 2").collect { [it.id, it.name] }
    }

    @Unroll
    def "UPDATE | should fail to update a non-existent location"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new Location(null, "Test", "Addr", "City", "ST", "12345", "US", createPoint())

        when: "an update is attempted"
        locationController.updateLocation(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    /********** DELETE Tests **********/

    @Unroll
    def "DELETE | should remove an existing location"() {
        given: "a new location to be deleted"
        def tempLoc = new Location(null, "Test Delete Location", "123 Delete St", "Delete City", "UT", "00000", "US", createPoint())
        def saved = locationController.addLocation(tempLoc)
        UUID id = saved.id()
        assert locationRepository.existsById(id)

        when: "the location is deleted"
        locationController.deleteById(id)

        then: "the location no longer exists in the repository or database"
        verifyAll {
            !locationRepository.findById(id).isPresent()
            new Sql(connection).firstRow("SELECT count(*) as count FROM locations WHERE id = ?", [id]).count == 0
        }
    }

    @Unroll
    def "DELETE | should handle deletion of non-existent location gracefully"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted"
        locationController.deleteById(nonExistentId)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** LIST Tests **********/

    @Unroll
    def "LIST | should fully drain all locations sequentially using cursors"() {
        setup:
        Set<Location> allLocations = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("name")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<Location> page = locationController.getLocations(pageable)
            allLocations.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all locations from the database"
        def totalCount = new Sql(connection).firstRow("SELECT count(*) as count FROM locations").count
        verifyAll {
            allLocations.size() == totalCount
            allLocations.size() >= 2
        }
    }
}
