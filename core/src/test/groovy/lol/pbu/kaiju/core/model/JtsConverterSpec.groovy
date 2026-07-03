package lol.pbu.kaiju.core.model

import io.micronaut.core.convert.ConversionContext
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.postgresql.util.PGobject
import spock.lang.Specification

import java.sql.SQLException

class JtsConverterSpec extends Specification {

    JtsPointConverter pointConverter = new JtsPointConverter()
    JtsPolygonConverter polygonConverter = new JtsPolygonConverter()
    GeometryFactory geometryFactory = new GeometryFactory()

    /********** JtsPointConverter Tests **********/

    def "JtsPointConverter | should convert point to PGobject and back successfully"() {
        given: "a valid JTS point"
        Point point = geometryFactory.createPoint(new Coordinate(-105.0, 39.0))
        point.setSRID(4326)

        when: "converting to persisted value"
        PGobject pgObject = pointConverter.convertToPersistedValue(point, ConversionContext.DEFAULT)

        then: "it maps to a PGobject with geography type"
        pgObject != null
        pgObject.type == "geography"
        pgObject.value != null

        when: "converting back to entity value"
        Point convertedPoint = pointConverter.convertToEntityValue(pgObject, ConversionContext.DEFAULT)

        then: "it reconstructs the point with matching coordinates and SRID"
        convertedPoint != null
        convertedPoint.x == Double.valueOf(-105.0)
        convertedPoint.y == Double.valueOf(39.0)
        convertedPoint.SRID == 4326
    }

    def "JtsPointConverter | should handle null values gracefully"() {
        expect:
        pointConverter.convertToPersistedValue(null, ConversionContext.DEFAULT) == null
        pointConverter.convertToEntityValue(null, ConversionContext.DEFAULT) == null
        pointConverter.convertToEntityValue(new PGobject(), ConversionContext.DEFAULT) == null
    }

    def "JtsPointConverter | should return null on ParseException for invalid hex or WKB value"() {
        given: "a PGobject with malformed geometry representation"
        PGobject pgObject = new PGobject()
        pgObject.type = "geography"
        pgObject.value = "414243" // ABC in hex - valid hex but not a valid point WKB

        when: "converting to entity value"
        Point result = pointConverter.convertToEntityValue(pgObject, ConversionContext.DEFAULT)

        then: "it returns null rather than throwing ParseException"
        result == null
    }

    def "JtsPointConverter | should return null on SQLException when PGobject throws"() {
        given: "a valid point"
        Point point = geometryFactory.createPoint(new Coordinate(-105.0, 39.0))

        and: "a subclass of JtsPointConverter that overrides createPGobject to throw SQLException"
        JtsPointConverter throwingConverter = new JtsPointConverter() {
            @Override
            protected PGobject createPGobject() {
                return new PGobject() {
                    @Override
                    void setValue(String value) throws SQLException {
                        throw new SQLException("Simulated write error")
                    }
                }
            }
        }

        when: "converting to persisted value"
        PGobject result = throwingConverter.convertToPersistedValue(point, ConversionContext.DEFAULT)

        then: "the SQLException is caught and null is returned"
        result == null
    }

    def "JtsPointConverter | should default SRID to 4326 if SRID is 0"() {
        given: "a point with SRID = 0"
        Point point = geometryFactory.createPoint(new Coordinate(-105.0, 39.0))
        point.setSRID(0)

        when: "converting to persisted value and back"
        PGobject pgObject = pointConverter.convertToPersistedValue(point, ConversionContext.DEFAULT)
        Point result = pointConverter.convertToEntityValue(pgObject, ConversionContext.DEFAULT)

        then: "the SRID is set to 4326"
        result.SRID == 4326
    }

    def "JtsPointConverter | should preserve non-zero SRID"() {
        given: "a point with SRID = 3857"
        Point point = geometryFactory.createPoint(new Coordinate(-105.0, 39.0))
        point.setSRID(3857)

        when: "converting to persisted value and back"
        PGobject pgObject = pointConverter.convertToPersistedValue(point, ConversionContext.DEFAULT)
        Point result = pointConverter.convertToEntityValue(pgObject, ConversionContext.DEFAULT)

        then: "the SRID remains 3857"
        result.SRID == 3857
    }

    /********** JtsPolygonConverter Tests **********/

    private Polygon createTestPolygon() {
        Coordinate[] coords = [
                new Coordinate(-105.0, 39.0),
                new Coordinate(-104.0, 39.0),
                new Coordinate(-104.0, 40.0),
                new Coordinate(-105.0, 40.0),
                new Coordinate(-105.0, 39.0)
        ]
        return geometryFactory.createPolygon(coords)
    }

    def "JtsPolygonConverter | should convert polygon to PGobject and back successfully"() {
        given: "a valid JTS polygon"
        Polygon polygon = createTestPolygon()
        polygon.setSRID(4326)

        when: "converting to persisted value"
        Object pgObject = polygonConverter.convertToPersistedValue(polygon, ConversionContext.DEFAULT)

        then: "it maps to a PGobject with geography type"
        pgObject instanceof PGobject
        ((PGobject) pgObject).type == "geography"
        ((PGobject) pgObject).value != null

        when: "converting back to entity value"
        Object convertedPolygon = polygonConverter.convertToEntityValue(pgObject, ConversionContext.DEFAULT)

        then: "it reconstructs the polygon with matching SRID"
        convertedPolygon instanceof Polygon
        ((Polygon) convertedPolygon).SRID == 4326
    }

    def "JtsPolygonConverter | should handle null values gracefully"() {
        expect:
        polygonConverter.convertToPersistedValue(null, ConversionContext.DEFAULT) == null
        polygonConverter.convertToEntityValue(null, ConversionContext.DEFAULT) == null
        polygonConverter.convertToEntityValue(new PGobject(), ConversionContext.DEFAULT) == null
    }

    def "JtsPolygonConverter | should return null on ParseException for invalid hex or WKB value"() {
        given: "a PGobject with malformed geometry representation"
        PGobject pgObject = new PGobject()
        pgObject.type = "geography"
        pgObject.value = "414243" // ABC in hex - valid hex but not a valid polygon WKB

        when: "converting to entity value"
        Object result = polygonConverter.convertToEntityValue(pgObject, ConversionContext.DEFAULT)

        then: "it returns null rather than throwing ParseException"
        result == null
    }

    def "JtsPolygonConverter | should return null on SQLException when PGobject throws"() {
        given: "a valid polygon"
        Polygon polygon = createTestPolygon()

        and: "a subclass of JtsPolygonConverter that overrides createPGobject to throw SQLException"
        JtsPolygonConverter throwingConverter = new JtsPolygonConverter() {
            @Override
            protected PGobject createPGobject() {
                return new PGobject() {
                    @Override
                    void setValue(String value) throws SQLException {
                        throw new SQLException("Simulated write error")
                    }
                }
            }
        }

        when: "converting to persisted value"
        Object result = throwingConverter.convertToPersistedValue(polygon, ConversionContext.DEFAULT)

        then: "the SQLException is caught and null is returned"
        result == null
    }

    def "JtsPolygonConverter | should default SRID to 4326 if SRID is 0"() {
        given: "a polygon with SRID = 0"
        Polygon polygon = createTestPolygon()
        polygon.setSRID(0)

        when: "converting to persisted value and back"
        Object pgObject = polygonConverter.convertToPersistedValue(polygon, ConversionContext.DEFAULT)
        Object result = polygonConverter.convertToEntityValue(pgObject, ConversionContext.DEFAULT)

        then: "the SRID is set to 4326"
        result instanceof Polygon
        ((Polygon) result).SRID == 4326
    }

    def "JtsPolygonConverter | should preserve non-zero SRID"() {
        given: "a polygon with SRID = 3857"
        Polygon polygon = createTestPolygon()
        polygon.setSRID(3857)

        when: "converting to persisted value and back"
        Object pgObject = polygonConverter.convertToPersistedValue(polygon, ConversionContext.DEFAULT)
        Object result = polygonConverter.convertToEntityValue(pgObject, ConversionContext.DEFAULT)

        then: "the SRID remains 3857"
        result instanceof Polygon
        ((Polygon) result).SRID == 3857
    }
}
