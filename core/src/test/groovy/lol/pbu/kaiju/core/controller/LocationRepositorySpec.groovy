package lol.pbu.kaiju.core.controller

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.core.domain.Location
import lol.pbu.kaiju.core.repository.LocationRepository
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.io.WKBWriter
import spock.lang.Specification

@MicronautTest
class LocationRepositorySpec extends Specification {

    @Inject
    LocationRepository locationRepository

    def "should save and load location"() {
        given:
        def factory = new GeometryFactory(new PrecisionModel(), 4326)
        def point = factory.createPoint(new Coordinate(1.0, 2.0))
        point.setSRID(4326)
        def wkbWriter = new WKBWriter(2, true)
        def wkb = wkbWriter.write(point)
        def location = new Location(null, "Test", "Addr", "City", "ST", "123", "US", wkb)

        when:
        def saved = locationRepository.save(location)

        then:
        saved.id() != null
        
        when:
        def loaded = locationRepository.findById(saved.id()).get()
        
        then:
        loaded.name() == "Test"
        loaded.geom() != null
    }
}
