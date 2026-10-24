package lol.pbu.kaiju.fixtures.builder

import jakarta.inject.Inject
import jakarta.inject.Singleton
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.BoundaryRepository
import lol.pbu.kaiju.repository.LocationRepository
import lol.pbu.kaiju.repository.TagRepository
import lol.pbu.kaiju.repository.UserRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

@Singleton
class KaijuDataBuilder {

    private final UserRepository userRepository
    private final AdministrativeRegionRepository regionRepository
    private final LocationRepository locationRepository
    private final TagRepository tagRepository
    private final BoundaryRepository boundaryRepository
    private final GeometryFactory geometryFactory
    private final Faker faker

    KaijuDataBuilder() {
        this(null, null, null, null, null)
    }

    @Inject
    KaijuDataBuilder(
            UserRepository userRepository,
            AdministrativeRegionRepository regionRepository,
            LocationRepository locationRepository,
            TagRepository tagRepository,
            BoundaryRepository boundaryRepository
    ) {
        this.userRepository = userRepository
        this.regionRepository = regionRepository
        this.locationRepository = locationRepository
        this.tagRepository = tagRepository
        this.boundaryRepository = boundaryRepository
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)
        this.faker = new Faker()
    }

    UserBuilder user() {
        return new UserBuilder(userRepository, faker)
    }

    RegionBuilder region() {
        return new RegionBuilder(regionRepository, geometryFactory, faker)
    }

    LocationBuilder location() {
        return new LocationBuilder(locationRepository, geometryFactory, faker)
    }

    TagBuilder tag() {
        return new TagBuilder(tagRepository)
    }

    BoundaryBuilder boundary() {
        return new BoundaryBuilder(boundaryRepository, geometryFactory)
    }

    GeometryFactory getGeometryFactory() {
        return geometryFactory
    }

    Faker getFaker() {
        return faker
    }
}
