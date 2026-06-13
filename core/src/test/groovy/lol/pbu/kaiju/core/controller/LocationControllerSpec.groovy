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
import spock.lang.Specification

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

    def "should fully drain all locations sequentially using cursors"() {
        setup:
        Sql sql = new Sql(connection)
        Set<Location> allLocations = new LinkedHashSet<>()
        int pageSize = 1

        // Start with the initial un-anchored request
        def pageable = CursoredPageable.from(pageSize, Sort.unsorted())

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<Location> page = locationController.getLocations(pageable)
            allLocations.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then:
        allLocations.size() == locationRepository.count()

        List<Map> fields = allLocations.collect { [name: it.name(), address: it.addressLine(), city: it.city()] }
        List<Map> expectedFields = sql.rows("SELECT name, address_line as address, city FROM locations")

        fields.containsAll(expectedFields)
    }


    def "GetLocation"() {
        setup:
        Sql sql = new Sql(connection)
        def firstLocationId = sql.firstRow("SELECT id FROM locations LIMIT 1").id as UUID

        when:
        Optional<Location> result = locationController.getLocation(firstLocationId)

        then:
        result.isPresent()
        result.get().id() == firstLocationId
        result.get().name() == sql.firstRow("SELECT name FROM locations WHERE id = ?", [firstLocationId]).name
    }

    def "AddLocation"() {
        setup:
        Sql sql = new Sql(connection)
        def newLocation = new Location(null, "New Test Location", "101 Test St", "Test City", "UT", "84000", null)

        when:
        Location saved = locationController.addLocation(newLocation)

        then:
        saved.id() != null
        saved.name() == "New Test Location"
        sql.firstRow("SELECT count(*) as count FROM locations WHERE name = 'New Test Location'").count == 1
    }

    def "UpdateLocation"() {
        setup:
        Sql sql = new Sql(connection)
        def firstLocation = sql.firstRow("SELECT id, name FROM locations LIMIT 1")
        def originalId = firstLocation.id as UUID
        def updatedName = "Updated " + firstLocation.name
        def updateRequest = new Location(null, updatedName, "Updated Address", "Updated City", "UT", "84000", null)

        when:
        Location updated = locationController.updateLocation(originalId, updateRequest)

        then:
        updated.id() == originalId
        updated.name() == updatedName
        sql.firstRow("SELECT name FROM locations WHERE id = ?", [originalId]).name == updatedName
    }

    def "DeleteLocation"() {
        setup:
        Sql sql = new Sql(connection)
        def locationToDelete = sql.firstRow("SELECT id FROM locations ORDER BY name DESC LIMIT 1")
        def idToDelete = locationToDelete.id as UUID

        when:
        locationController.deleteById(idToDelete)

        then:
        !locationRepository.findById(idToDelete).isPresent()
        sql.firstRow("SELECT count(*) as count FROM locations WHERE id = ?", [idToDelete]).count == 0
    }
}
