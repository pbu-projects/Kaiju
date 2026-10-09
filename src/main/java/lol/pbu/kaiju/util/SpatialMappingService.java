package lol.pbu.kaiju.util;

import jakarta.inject.Singleton;
import lol.pbu.kaiju.dto.CoordinateDto;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class SpatialMappingService {
    private final GeometryFactory geometryFactory;

    public SpatialMappingService(GeometryFactory geometryFactory) {
        this.geometryFactory = geometryFactory;
    }

    public Point toPoint(Double longitude, Double latitude) {
        if (longitude == null || latitude == null) {
            return null;
        }
        return geometryFactory.createPoint(new Coordinate(longitude, latitude));
    }

    public Polygon toPolygon(List<CoordinateDto> dtos) {
        if (dtos == null || dtos.size() < 3) {
            throw new IllegalArgumentException("A polygon requires at least 3 coordinates.");
        }
        List<Coordinate> coords = new ArrayList<>(dtos.size() + 1);
        for (CoordinateDto dto : dtos) {
            coords.add(new Coordinate(dto.longitude(), dto.latitude()));
        }
        // Ensure polygon ring closure
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (!first.equals2D(last)) {
            coords.add(new Coordinate(first.x, first.y));
        }
        LinearRing ring = geometryFactory.createLinearRing(coords.toArray(new Coordinate[0]));
        return geometryFactory.createPolygon(ring);
    }
}
