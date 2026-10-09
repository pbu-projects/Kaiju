package lol.pbu.kaiju.util

import lol.pbu.kaiju.dto.CoordinateDto
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Specification

class SpatialMappingServiceSpec extends Specification {

    GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)
    SpatialMappingService service = new SpatialMappingService(geometryFactory)

    def "toPoint returns null when coordinates are null"() {
        expect:
        service.toPoint(null, null) == null
        service.toPoint(10.0, null) == null
        service.toPoint(null, 20.0) == null
    }

    def "toPoint creates JTS point with correct coordinates and srid"() {
        when:
        def point = service.toPoint(-104.9903, 39.7392)

        then:
        point != null
        point.x == -104.9903
        point.y == 39.7392
        point.getSRID() == 4326
    }

    def "toPolygon throws exception when coordinates are null or fewer than 3"() {
        when:
        service.toPolygon(null)

        then:
        thrown(IllegalArgumentException)

        when:
        service.toPolygon([new CoordinateDto(0.0, 0.0), new CoordinateDto(1.0, 1.0)])

        then:
        thrown(IllegalArgumentException)
    }

    def "toPolygon automatically closes open ring"() {
        given:
        def coords = [
                new CoordinateDto(0.0, 0.0),
                new CoordinateDto(1.0, 0.0),
                new CoordinateDto(1.0, 1.0)
        ]

        when:
        def polygon = service.toPolygon(coords)

        then:
        polygon != null
        polygon.coordinates.length == 4
        polygon.coordinates[0].x == 0.0
        polygon.coordinates[0].y == 0.0
        polygon.coordinates[3].x == 0.0
        polygon.coordinates[3].y == 0.0
        polygon.getSRID() == 4326
    }

    def "toPolygon does not duplicate closing coordinate if already closed"() {
        given:
        def coords = [
                new CoordinateDto(0.0, 0.0),
                new CoordinateDto(1.0, 0.0),
                new CoordinateDto(1.0, 1.0),
                new CoordinateDto(0.0, 0.0)
        ]

        when:
        def polygon = service.toPolygon(coords)

        then:
        polygon != null
        polygon.coordinates.length == 4
        polygon.coordinates[0].equals2D(polygon.coordinates[3])
    }
}
