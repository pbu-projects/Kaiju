package lol.pbu.kaiju.core.controller

import groovy.sql.Sql
import io.micronaut.core.io.ResourceLoader
import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
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

@MicronautTest
class LocationControllerSpec extends Specification {

    @Inject
    Connection connection

    @Inject
    ResourceLoader resourceLoader

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

    def cleanup() {
        try {
            new Sql(connection).execute("DELETE FROM locations WHERE name LIKE 'Faker %' OR name LIKE 'Updated %' OR name LIKE 'Test %'")
        } catch (Exception ignore) {
        }
    }

    @Unroll
    def "should successfully save and retrieve diverse international locations: #country"() {
        setup:
        Sql sql = new Sql(connection)
        def newLocation = new Location(
                null,
                "Faker ",
                address,
                city,
                stateProvince,
                postalCode,
                countryCode,
                createPoint(lon, lat)
        )

        when:
        Location saved = locationController.addLocation(newLocation)

        then:
        saved.id() != null
        saved.countryCode() == countryCode


        where:
        country     | name             | address            | city        | stateProvince | postalCode | countryCode | lon      | lat
        "UK"        | "London Pantry"  | "10 Downing St"    | "London"    | null          | "SW1A 2AA" | "GB"        | -0.1276  | 51.5072
        "Canada"    | "Toronto Hub"    | "1 Front St W"     | "Toronto"   | "ON"          | "M5J 2X5"  | "CA"        | -79.3786 | 43.6465
        "UAE"       | "Dubai Center"   | "Burj Khalifa"     | "Dubai"     | null          | null       | "AE"        | 55.2744  | 25.1972
        "Chile"     | "Santiago Post"  | "Plaza de Armas"   | "Santiago"  | null          | null       | "CL"        | -70.6506 | -33.4372
        "Japan"     | "Tokyo Pantry"   | "千代田区1-1"       | "東京都"     | null          | "100-0001" | "JP"        | 139.7528 | 35.6852
        "Germany"   | "Berlin Office"  | "Straße des 17."   | "München"   | "BY"          | "80331"    | "DE"        | 11.5755  | 48.1372
        "Australia" | "Sydney Food"    | "Sydney Opera"     | "Sydney"    | "NSW"         | "2000"     | "AU"        | 151.2153 | -33.8568
    }

    @Unroll
    def "not nullable field(s) should not be null"() {
        when:
        locationController.addLocation(location)

        then:
        thrown(Exception)

        where:
        location << {
            def names = ["Valid Name", null, ""]
            def addresses = ["Valid Address", null, ""]
            def cities = ["Valid City", null, ""]
            def countries = ["US", null, "", "USA", "A"]
            def geoms = [createPoint(0, 0), null]

            def combinations = [names, addresses, cities, countries, geoms].combinations().collect { n, a, c, co, g ->
                new Location(null, n, a, c, "State", "12345", co, g)
            }

            combinations.findAll { loc ->
                loc.name() == null || loc.name().isBlank() ||
                loc.address() == null || loc.address().isBlank() ||
                loc.city() == null || loc.city().isBlank() ||
                loc.countryCode() == null || loc.countryCode().length() != 2 ||
                loc.geom() == null
            }
        }()
    }

    def "GetLocation for dynamic records should return correct location"() {
        setup:
        Sql sql = new Sql(connection)
        def names = sql.rows("SELECT name FROM locations LIMIT 2").collect { it.name }
        
        expect:
        names.each { expectedName ->
            def location = sql.firstRow("SELECT id, name FROM locations WHERE name = ?", [expectedName])
            UUID id = location.id as UUID
            Optional<Location> result = locationController.getLocation(id)
            assert result.isPresent()
            assert result.get().id() == id
            assert result.get().name() == expectedName
        }
    }

    def "UpdateLocation should persist changes"() {
        setup:
        Sql sql = new Sql(connection)
        def firstLocation = sql.firstRow("SELECT id FROM locations ORDER BY name ASC LIMIT 1")
        UUID id = firstLocation.id as UUID
        String newName = "Updated Location Name"
        String newCity = "New Test City"
        def updateRequest = new Location(null, newName, "Updated Address", newCity, "UT", "84000", "US", createPoint())

        when:
        Location updated = locationController.updateLocation(id, updateRequest)

        then:
        updated.id() == id
        updated.name() == newName
        updated.city() == newCity
        sql.firstRow("SELECT name, city FROM locations WHERE id = ?", [id]).with {
            name == newName && city == newCity
        }
    }

    def "DeleteLocation should remove the record"() {
        setup:
        Sql sql = new Sql(connection)
        def tempLoc = new Location(
            null, "Test Delete Location", "123 Delete St", "Delete City", "UT", "00000", "US", createPoint()
        )
        def saved = locationController.addLocation(tempLoc)
        UUID id = saved.id()

        when:
        locationController.deleteById(id)

        then:
        !locationRepository.findById(id).isPresent()
        sql.firstRow("SELECT count(*) as count FROM locations WHERE id = ?", [id]).count == 0
    }

    def "should fully drain all locations sequentially using cursors"() {
        setup:
        Sql sql = new Sql(connection)
        Set<Location> allLocations = new LinkedHashSet<>()
        int pageSize = 10

        def pageable = CursoredPageable.from(pageSize, Sort.unsorted())

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<Location> page = locationController.getLocations(pageable)
            allLocations.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then:
        allLocations.size() >= 2
    }
}
