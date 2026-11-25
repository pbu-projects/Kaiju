package lol.pbu.kaiju.service;

import jakarta.inject.Singleton;
import lol.pbu.kaiju.dto.Coordinates;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class SpatialMappingService {

    public static final int MAX_VERTICES = 10_000;

    private final GeometryFactory geometryFactory;

    public SpatialMappingService(GeometryFactory geometryFactory) {
        this.geometryFactory = geometryFactory;
    }

    public Point toPoint(Double longitude, Double latitude) {
        if (longitude == null || latitude == null) {
            return null;
        }
        validateCoordinate(longitude, latitude);
        return geometryFactory.createPoint(new Coordinate(longitude, latitude));
    }

    public Polygon toPolygon(List<Coordinates> coordinates) {
        if (coordinates == null || coordinates.size() < 3) {
            throw new IllegalArgumentException("A polygon requires at least 3 coordinates.");
        }
        if (coordinates.size() > MAX_VERTICES) {
            throw new IllegalArgumentException("A polygon cannot exceed " + MAX_VERTICES + " vertices.");
        }
        List<Coordinate> coords = new ArrayList<>(coordinates.stream()
                .map(this::toValidatedCoordinate)
                .toList());
        // Ensure polygon ring closure
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (!first.equals2D(last)) {
            coords.add(new Coordinate(first.x, first.y));
        }
        LinearRing ring = geometryFactory.createLinearRing(coords.toArray(new Coordinate[0]));
        return geometryFactory.createPolygon(ring);
    }

    private Coordinate toValidatedCoordinate(Coordinates coordinate) {
        if (coordinate == null || coordinate.longitude() == null || coordinate.latitude() == null) {
            throw new IllegalArgumentException("Polygon coordinates must not contain null elements.");
        }
        validateCoordinate(coordinate.longitude(), coordinate.latitude());
        return new Coordinate(coordinate.longitude(), coordinate.latitude());
    }

    private void validateCoordinate(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)) {
            throw new IllegalArgumentException("Coordinate values must be finite numbers.");
        }
        if (longitude < -180.0 || longitude > 180.0 || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException(
                    "Coordinate out of WGS84 bounds: longitude must be in [-180, 180] and latitude in [-90, 90]."
            );
        }
    }
}
