package lol.pbu.kaiju.domain

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import spock.lang.Specification

class LocationSpec extends Specification {

    private final GeometryFactory geometryFactory = new GeometryFactory()

    private Point createPoint(double lon = -104.9903, double lat = 39.7392) {
        return geometryFactory.createPoint(new Coordinate(lon, lat))
    }

    def "positive testing | constructor without timezone defaults to null"() {
        given: "location parameters"
        def id = UUID.randomUUID()
        def point = createPoint()

        when: "creating a location using the eight parameter constructor"
        def location = new Location(id, "Community Center", "123 Main St", "Denver", "CO", "80202", "US", point)

        then: "all fields are populated and timezone is null"
        location.id() == id
        location.name() == "Community Center"
        location.addressLine() == "123 Main St"
        location.city() == "Denver"
        location.stateProvince() == "CO"
        location.postalCode() == "80202"
        location.countryCode() == "US"
        location.geom() == point
        location.timezone() == null
    }

    def "positive testing | constructor with timezone stores given value"() {
        given: "location parameters with timezone"
        def id = UUID.randomUUID()
        def point = createPoint()

        when: "creating a location with all nine parameters"
        def location = new Location(id, "Community Center", "123 Main St", "Denver", "CO", "80202", "US", point, "America/Denver")

        then: "timezone is properly set"
        location.timezone() == "America/Denver"
    }

    def "positive testing | withId creates a new copy with the updated identifier"() {
        given: "an existing location"
        def originalId = UUID.randomUUID()
        def newId = UUID.randomUUID()
        def point = createPoint()
        def location = new Location(originalId, "Food Hub", "456 Market St", "Denver", "CO", "80202", "US", point, "America/Denver")

        when: "calling withId with a new identifier"
        def updated = location.withId(newId)

        then: "the new location has the new id while preserving all other properties"
        updated.id() == newId
        updated.name() == "Food Hub"
        updated.addressLine() == "456 Market St"
        updated.city() == "Denver"
        updated.stateProvince() == "CO"
        updated.postalCode() == "80202"
        updated.countryCode() == "US"
        updated.geom() == point
        updated.timezone() == "America/Denver"
    }

    def "positive testing | withTimezone creates a new copy with the updated timezone"() {
        given: "an existing location"
        def id = UUID.randomUUID()
        def point = createPoint()
        def location = new Location(id, "Food Hub", "456 Market St", "Denver", "CO", "80202", "US", point, null)

        when: "calling withTimezone with America/Denver"
        def updated = location.withTimezone("America/Denver")

        then: "the new location has the updated timezone while keeping everything else identical"
        updated.id() == id
        updated.name() == "Food Hub"
        updated.addressLine() == "456 Market St"
        updated.city() == "Denver"
        updated.stateProvince() == "CO"
        updated.postalCode() == "80202"
        updated.countryCode() == "US"
        updated.geom() == point
        updated.timezone() == "America/Denver"
    }
}
