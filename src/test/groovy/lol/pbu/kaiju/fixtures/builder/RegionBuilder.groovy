package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

import java.util.UUID

class RegionBuilder {

    private final AdministrativeRegionRepository regionRepository
    private final GeometryFactory geometryFactory
    private final Faker faker

    private UUID id
    private String name
    private UUID parentRegionId
    private Geometry geom

    RegionBuilder(
            AdministrativeRegionRepository regionRepository,
            GeometryFactory geometryFactory,
            Faker faker
    ) {
        this.regionRepository = regionRepository
        this.geometryFactory = geometryFactory
        this.faker = faker
    }

    RegionBuilder withName(String name) {
        this.name = name
        return this
    }

    RegionBuilder withParent(UUID parentRegionId) {
        this.parentRegionId = parentRegionId
        return this
    }

    RegionBuilder withParent(AdministrativeRegion parent) {
        this.parentRegionId = parent?.id()
        return this
    }

    RegionBuilder withGeometry(Geometry geom) {
        if (geom != null && geom.getSRID() == 0) {
            geom.setSRID(4326)
        }
        this.geom = geom
        return this
    }

    RegionBuilder withId(UUID id) {
        this.id = id
        return this
    }

    AdministrativeRegion build() {
        String regionName = name ?: "Region ${faker.address().state()} ${UUID.randomUUID().toString().substring(0, 8)}"
        Geometry geometry = geom ?: defaultPolygon()
        return new AdministrativeRegion(id, regionName, parentRegionId, geometry)
    }

    AdministrativeRegion persist() {
        assert regionRepository != null : "Cannot persist region without an AdministrativeRegionRepository"
        return regionRepository.save(build())
    }

    private Geometry defaultPolygon() {
        Coordinate[] coords = [
                new Coordinate(-105.0, 39.0),
                new Coordinate(-104.0, 39.0),
                new Coordinate(-104.0, 40.0),
                new Coordinate(-105.0, 40.0),
                new Coordinate(-105.0, 39.0)
        ]
        return geometryFactory.createPolygon(coords)
    }
}
