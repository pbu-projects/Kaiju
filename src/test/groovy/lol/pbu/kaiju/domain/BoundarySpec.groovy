package lol.pbu.kaiju.domain

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import spock.lang.Specification

class BoundarySpec extends Specification {

    private final GeometryFactory geometryFactory = new GeometryFactory()

    private Polygon createPolygon() {
        Coordinate[] coords = [
                new Coordinate(-105.0d, 39.0d),
                new Coordinate(-104.0d, 39.0d),
                new Coordinate(-104.0d, 40.0d),
                new Coordinate(-105.0d, 40.0d),
                new Coordinate(-105.0d, 39.0d)
        ] as Coordinate[]
        return geometryFactory.createPolygon(coords)
    }

    def "forId builds a stub boundary with only id set"() {
        given: "a boundary ID"
        UUID boundaryId = UUID.randomUUID()

        when: "calling Boundary.forId"
        Boundary boundary = Boundary.forId(boundaryId)

        then: "only id is populated"
        verifyAll(boundary) {
            id() == boundaryId
            name() == null
            geom() == null
        }
    }

    def "forCreation, forUpdate, and withId populate expected boundary fields"() {
        given: "a polygon and boundary identifiers"
        Polygon polygon = createPolygon()
        UUID id1 = UUID.randomUUID()
        UUID id2 = UUID.randomUUID()

        when: "using the factory and copy helpers"
        Boundary created = Boundary.forCreation("Denver County", polygon)
        Boundary updated = Boundary.forUpdate(id1, "Boulder County", polygon)
        Boundary reassigned = created.withId(id2)

        then: "each boundary carries the expected values"
        verifyAll {
            created.id() == null
            created.name() == "Denver County"
            created.geom() == polygon
            updated.id() == id1
            updated.name() == "Boulder County"
            updated.geom() == polygon
            reassigned.id() == id2
            reassigned.name() == "Denver County"
            reassigned.geom() == polygon
        }
    }
}
