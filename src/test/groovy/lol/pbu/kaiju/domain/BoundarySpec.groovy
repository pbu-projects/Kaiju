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

    def "forCreation builds an unsaved boundary with name and geometry"() {
        given: "a polygon"
        Polygon polygon = createPolygon()

        when: "calling Boundary.forCreation"
        Boundary created = Boundary.forCreation("Denver County", polygon)

        then: "id is null and name and geometry are set"
        verifyAll(created) {
            id() == null
            name() == "Denver County"
            geom() == polygon
        }
    }

    def "forUpdate builds a boundary with id, name, and geometry"() {
        given: "a polygon and boundary id"
        Polygon polygon = createPolygon()
        UUID boundaryId = UUID.randomUUID()

        when: "calling Boundary.forUpdate"
        Boundary updated = Boundary.forUpdate(boundaryId, "Boulder County", polygon)

        then: "id, name, and geometry are set"
        verifyAll(updated) {
            id() == boundaryId
            name() == "Boulder County"
            geom() == polygon
        }
    }

    def "withId returns a copy with the new id and keeps name and geometry"() {
        given: "an existing boundary and a new id"
        Polygon polygon = createPolygon()
        Boundary created = Boundary.forCreation("Denver County", polygon)
        UUID newId = UUID.randomUUID()

        when: "calling withId"
        Boundary reassigned = created.withId(newId)

        then: "the new id is set while name and geometry remain"
        verifyAll(reassigned) {
            id() == newId
            name() == "Denver County"
            geom() == polygon
        }
    }
}
