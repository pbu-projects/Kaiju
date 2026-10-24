package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.Boundary
import lol.pbu.kaiju.repository.BoundaryRepository
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory

import java.util.UUID

class BoundaryBuilder {

    private final BoundaryRepository boundaryRepository
    private final GeometryFactory geometryFactory

    private UUID id
    private String name
    private Geometry geom

    BoundaryBuilder(BoundaryRepository boundaryRepository, GeometryFactory geometryFactory) {
        this.boundaryRepository = boundaryRepository
        this.geometryFactory = geometryFactory
    }

    BoundaryBuilder withName(String name) {
        this.name = name
        return this
    }

    BoundaryBuilder withGeometry(Geometry geom) {
        if (geom != null && geom.getSRID() == 0) {
            geom.setSRID(4326)
        }
        this.geom = geom
        return this
    }

    BoundaryBuilder withId(UUID id) {
        this.id = id
        return this
    }

    Boundary build() {
        String boundaryName = name ?: "Boundary ${UUID.randomUUID().toString().substring(0, 8)}"
        Geometry geometry = geom ?: defaultPolygon()
        return new Boundary(id, boundaryName, geometry)
    }

    Boundary persist() {
        assert boundaryRepository != null : "Cannot persist boundary without a BoundaryRepository"
        return boundaryRepository.save(build())
    }

    private Geometry defaultPolygon() {
        Coordinate[] coords = [
                new Coordinate(0.0, 0.0),
                new Coordinate(1.0, 0.0),
                new Coordinate(1.0, 1.0),
                new Coordinate(0.0, 1.0),
                new Coordinate(0.0, 0.0)
        ]
        return geometryFactory.createPolygon(coords)
    }
}
