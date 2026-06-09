package lol.pbu.kaiju.core.controller

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
        allLocations.size() == 3
        List<LinkedHashMap<String, String>> fields = allLocations.collect { [name: it.name(), address: it.addressLine(), city: it.city()] }

        fields.containsAll([[name: 'Main Pantry', address: '123 Main St', city: 'Bountiful'],
                            [name: 'North Sorting Facility', address: '456 Center St', city: 'Centerville'],
                            [name: 'Ogden Warehouse', address: '789 Far Ave', city: 'Ogden']])
    }

    def "GetLocation"() {}

    def "AddLocation"() {}

    def "UpdateLocation"() {}

    def "DeleteLocation"() {}
}
