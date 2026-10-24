package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.repository.LocationRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point

import java.util.UUID

class LocationBuilder {

    private final LocationRepository locationRepository
    private final GeometryFactory geometryFactory
    private final Faker faker

    private UUID id
    private String name
    private String addressLine = "123 Main Street"
    private String city = "Denver"
    private String stateProvince = "CO"
    private String postalCode = "80202"
    private String countryCode = "US"
    private Point geom
    private String timezone = "America/Denver"

    LocationBuilder(
            LocationRepository locationRepository,
            GeometryFactory geometryFactory,
            Faker faker
    ) {
        this.locationRepository = locationRepository
        this.geometryFactory = geometryFactory
        this.faker = faker
    }

    LocationBuilder withName(String name) {
        this.name = name
        return this
    }

    LocationBuilder atPoint(double lon, double lat) {
        this.geom = geometryFactory.createPoint(new Coordinate(lon, lat))
        return this
    }

    LocationBuilder withGeometry(Point geom) {
        if (geom != null && geom.getSRID() == 0) {
            geom.setSRID(4326)
        }
        this.geom = geom
        return this
    }

    LocationBuilder withTimezone(String timezone) {
        this.timezone = timezone
        return this
    }

    LocationBuilder withAddress(
            String addressLine,
            String city,
            String stateProvince,
            String postalCode,
            String countryCode
    ) {
        this.addressLine = addressLine
        this.city = city
        this.stateProvince = stateProvince
        this.postalCode = postalCode
        this.countryCode = countryCode
        return this
    }

    LocationBuilder withAddressLine(String addressLine) {
        this.addressLine = addressLine
        return this
    }

    LocationBuilder withCity(String city) {
        this.city = city
        return this
    }

    LocationBuilder withStateProvince(String stateProvince) {
        this.stateProvince = stateProvince
        return this
    }

    LocationBuilder withPostalCode(String postalCode) {
        this.postalCode = postalCode
        return this
    }

    LocationBuilder withCountryCode(String countryCode) {
        this.countryCode = countryCode
        return this
    }

    LocationBuilder withId(UUID id) {
        this.id = id
        return this
    }

    Location build() {
        String locationName = name ?: "Location ${faker.company().name()} ${UUID.randomUUID().toString().substring(0, 8)}"
        Point point = geom ?: geometryFactory.createPoint(new Coordinate(-104.9903, 39.7392))
        return new Location(
                id,
                locationName,
                addressLine,
                city,
                stateProvince,
                postalCode,
                countryCode,
                point,
                timezone
        )
    }

    Location persist() {
        assert locationRepository != null : "Cannot persist location without a LocationRepository"
        return locationRepository.save(build())
    }
}
