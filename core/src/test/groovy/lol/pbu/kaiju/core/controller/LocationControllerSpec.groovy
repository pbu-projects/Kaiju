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
            // Ignore failures during cleanup (e.g. aborted transactions)
        }
    }

    @Unroll
    def "should successfully save and retrieve diverse international locations: #country"() {
        setup:
        Sql sql = new Sql(connection)
        def newLocation = new Location(
                null,
                "Faker $name",
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

        // Verification for Australia using PostGIS radius search
        if (country == "Australia") {
            def row = sql.firstRow("""
                SELECT id FROM locations 
                WHERE ST_DWithin(geom, ST_GeographyFromText('POINT($lon $lat)'), 1000)
                AND id = ?
            """, [saved.id()])
            assert row != null
        }

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
    def "should fail database constraints for missing required fields"() {
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

            // Filter out the single completely valid combination
            combinations.findAll { loc ->
                loc.name() == null || loc.name().isBlank() ||
                loc.addressLine() == null || loc.addressLine().isBlank() ||
                loc.city() == null || loc.city().isBlank() ||
                loc.countryCode() == null || loc.countryCode().length() != 2 ||
                loc.geom() == null
            }
        }()
    }

    @Unroll
    def "GetLocation for #expectedName should return correct location"() {
        setup:
        Sql sql = new Sql(connection)
        def location = sql.firstRow("SELECT id, name FROM locations WHERE name = ?", [expectedName])
        UUID id = location.id as UUID

        when:
        Optional<Location> result = locationController.getLocation(id)

        then:
        result.isPresent()
        result.get().id() == id
        result.get().name() == expectedName

        where:
        expectedName << ["Main Pantry", "North Sorting Facility"]
    }

    @Unroll
    def "UpdateLocation for name '#newName' in city '#newCity' should persist changes"() {
        setup:
        Sql sql = new Sql(connection)
        def firstLocation = sql.firstRow("SELECT id FROM locations WHERE name = 'Main Pantry'")
        UUID id = firstLocation.id as UUID
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

        where:
        [newName, newCity] << [
            ["Updated Main"],
            ["Salt Lake City"]
        ].combinations()
    }

    @Unroll
    def "DeleteLocation for '#targetName' should remove the record"() {
        setup:
        Sql sql = new Sql(connection)
        def locationToDelete = sql.firstRow("SELECT id FROM locations WHERE name = ?", [targetName])
        UUID id = locationToDelete.id as UUID

        when:
        locationController.deleteById(id)

        then:
        !locationRepository.findById(id).isPresent()
        sql.firstRow("SELECT count(*) as count FROM locations WHERE id = ?", [id]).count == 0

        where:
        targetName << ["Ogden Warehouse"]
    }

    @Unroll
    def "should fully drain all locations sequentially using cursors"() {
        setup:
        Sql sql = new Sql(connection)
        Set<Location> allLocations = new LinkedHashSet<>()
        int pageSize = 1

        def pageable = CursoredPageable.from(pageSize, Sort.unsorted())

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<Location> page = locationController.getLocations(pageable)
            allLocations.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then:
        allLocations.size() >= 2 // At least what's left after other tests
        
        List<Map> fields = allLocations.collect { [name: it.name(), address: it.addressLine(), city: it.city()] }
        List<Map> expectedFields = sql.rows("SELECT name, address_line as address, city FROM locations").collect { 
            [name: it.name, address: it.address, city: it.city] 
        }

        fields.containsAll(expectedFields)
    }
}
