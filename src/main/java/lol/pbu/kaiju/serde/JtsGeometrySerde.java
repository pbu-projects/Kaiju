package lol.pbu.kaiju.serde;

import io.micronaut.core.type.Argument;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serde;
import io.micronaut.serde.exceptions.SerdeException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static io.micronaut.core.type.Argument.OBJECT_ARGUMENT;

/**
 * Micronaut Serde serializer and deserializer for JTS {@link Geometry} objects and RFC 7946 GeoJSON.
 *
 * <p>LocationTech JTS and GeoJSON represent spatial structures under the {@link Geometry} type hierarchy
 * (e.g., Point, Polygon, MultiPolygon) in memory and over HTTP, even though the Kaiju persistence layer
 * exclusively uses PostGIS {@code geography} (WGS 84 / EPSG:4326) for geodesic real-world accuracy.
 * This class serves solely as the HTTP wire adapter; no separate Cartesian geometry classes are needed.</p>
 */
@Singleton
public class JtsGeometrySerde implements Serde<Geometry> {

    public static final int MAX_VERTICES = 10_000;

    private static final String TYPE_KEY = "type";
    private static final String COORDINATES_KEY = "coordinates";

    private final GeometryFactory geometryFactory;

    public JtsGeometrySerde() {
        this(new GeometryFactory(new PrecisionModel(), 4326));
    }

    @Inject
    public JtsGeometrySerde(@Nullable GeometryFactory geometryFactory) {
        this.geometryFactory = geometryFactory != null
                ? geometryFactory
                : new GeometryFactory(new PrecisionModel(), 4326);
    }

    public GeometryFactory getGeometryFactory() {
        return geometryFactory;
    }

    @Override
    public void serialize(
            @NonNull Encoder encoder,
            @NonNull EncoderContext context,
            @NonNull Argument<? extends Geometry> type,
            @Nullable Geometry value
    ) throws IOException {
        if (value == null) {
            encoder.encodeNull();
            return;
        }

        Encoder objEncoder = encoder.encodeObject(type);
        switch (value) {
            case Polygon polygon -> writePolygon(objEncoder, polygon);
            case MultiPolygon multiPolygon -> writeMultiPolygon(objEncoder, multiPolygon);
            case Point point -> writePoint(objEncoder, point);
            default -> throw new SerdeException("Unsupported geometry type for serialization: " + value.getGeometryType());
        }
        objEncoder.finishStructure();
    }

    private void writePolygon(Encoder encoder, Polygon polygon) throws IOException {
        encoder.encodeKey(TYPE_KEY);
        encoder.encodeString("Polygon");

        encoder.encodeKey(COORDINATES_KEY);
        Encoder ringsEncoder = encoder.encodeArray(OBJECT_ARGUMENT);
        writeRing(ringsEncoder, polygon.getExteriorRing());
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            writeRing(ringsEncoder, polygon.getInteriorRingN(i));
        }
        ringsEncoder.finishStructure();
    }

    private void writeMultiPolygon(Encoder encoder, MultiPolygon multiPolygon) throws IOException {
        encoder.encodeKey(TYPE_KEY);
        encoder.encodeString("MultiPolygon");

        encoder.encodeKey(COORDINATES_KEY);
        Encoder polysEncoder = encoder.encodeArray(OBJECT_ARGUMENT);
        for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
            Polygon poly = (Polygon) multiPolygon.getGeometryN(i);
            Encoder ringsEncoder = polysEncoder.encodeArray(OBJECT_ARGUMENT);
            writeRing(ringsEncoder, poly.getExteriorRing());
            for (int j = 0; j < poly.getNumInteriorRing(); j++) {
                writeRing(ringsEncoder, poly.getInteriorRingN(j));
            }
            ringsEncoder.finishStructure();
        }
        polysEncoder.finishStructure();
    }

    private void writeRing(Encoder ringsEncoder, LineString ring) throws IOException {
        Encoder ringEncoder = ringsEncoder.encodeArray(OBJECT_ARGUMENT);
        for (Coordinate coord : ring.getCoordinates()) {
            writeCoordinate(ringEncoder, coord);
        }
        ringEncoder.finishStructure();
    }

    private void writePoint(Encoder encoder, Point point) throws IOException {
        encoder.encodeKey(TYPE_KEY);
        encoder.encodeString("Point");

        encoder.encodeKey(COORDINATES_KEY);
        Encoder coordEncoder = encoder.encodeArray(OBJECT_ARGUMENT);
        writeCoordinateElements(coordEncoder, point.getCoordinate());
        coordEncoder.finishStructure();
    }

    private void writeCoordinate(Encoder parentArrayEncoder, Coordinate coord) throws IOException {
        Encoder coordEncoder = parentArrayEncoder.encodeArray(OBJECT_ARGUMENT);
        writeCoordinateElements(coordEncoder, coord);
        coordEncoder.finishStructure();
    }

    private void writeCoordinateElements(Encoder coordEncoder, Coordinate coord) throws IOException {
        coordEncoder.encodeDouble(coord.getX());
        coordEncoder.encodeDouble(coord.getY());
        if (!Double.isNaN(coord.getZ())) {
            coordEncoder.encodeDouble(coord.getZ());
        }
    }

    @Override
    public Geometry deserialize(
            @NonNull Decoder decoder,
            @NonNull DecoderContext context,
            @NonNull Argument<? super Geometry> type
    ) throws IOException {
        JsonNode node = decoder.decodeNode();
        return deserializeFromNode(node, true);
    }

    public Geometry deserializeFromNode(@Nullable JsonNode node) throws IOException {
        return deserializeFromNode(node, false);
    }

    private Geometry deserializeFromNode(@Nullable JsonNode node, boolean strictLonBounds) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new SerdeException("GeoJSON geometry must be a JSON object");
        }
        JsonNode typeNode = node.get(TYPE_KEY);
        if (typeNode == null || !typeNode.isString()) {
            throw new SerdeException("Missing or invalid 'type' field in GeoJSON geometry");
        }
        String geomType = typeNode.getStringValue();
        JsonNode coordsNode = node.get(COORDINATES_KEY);
        if (coordsNode == null || !coordsNode.isArray()) {
            throw new SerdeException("Missing or invalid 'coordinates' field in GeoJSON geometry");
        }

        int[] vertexCount = {0};
        return switch (geomType.toLowerCase()) {
            case "polygon" -> parsePolygon(coordsNode, vertexCount, strictLonBounds);
            case "multipolygon" -> parseMultiPolygon(coordsNode, vertexCount, strictLonBounds);
            case "point" -> parsePoint(coordsNode, vertexCount, strictLonBounds);
            default -> throw new SerdeException("Unsupported geometry type: " + geomType);
        };
    }

    private Point parsePoint(JsonNode coordsNode, int[] vertexCount, boolean strictLonBounds) throws IOException {
        Coordinate coord = parseCoordinate(coordsNode, vertexCount, strictLonBounds);
        return geometryFactory.createPoint(coord);
    }

    private Polygon parsePolygon(JsonNode coordsNode, int[] vertexCount, boolean strictLonBounds) throws IOException {
        if (coordsNode.size() == 0) {
            throw new SerdeException("Polygon coordinates array must not be empty");
        }
        if (coordsNode.size() > MAX_VERTICES) {
            throw new SerdeException("Polygon exceeds maximum allowed rings/vertices of " + MAX_VERTICES);
        }
        JsonNode shellNode = coordsNode.get(0);
        if (shellNode == null || shellNode.isNull() || !shellNode.isArray()) {
            throw new SerdeException("Polygon exterior ring must be an array of coordinates");
        }
        LinearRing shell = parseLinearRing(shellNode, vertexCount, strictLonBounds);
        int numHoles = coordsNode.size() - 1;
        LinearRing[] holes = new LinearRing[numHoles];
        for (int i = 0; i < numHoles; i++) {
            JsonNode holeNode = coordsNode.get(i + 1);
            if (holeNode == null || holeNode.isNull() || !holeNode.isArray()) {
                throw new SerdeException("Polygon interior ring must be an array of coordinates");
            }
            holes[i] = parseLinearRing(holeNode, vertexCount, strictLonBounds);
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    private MultiPolygon parseMultiPolygon(JsonNode coordsNode, int[] vertexCount, boolean strictLonBounds) throws IOException {
        int numPolys = coordsNode.size();
        if (numPolys > MAX_VERTICES) {
            throw new SerdeException("MultiPolygon exceeds maximum allowed polygons/vertices of " + MAX_VERTICES);
        }
        Polygon[] polygons = new Polygon[numPolys];
        for (int i = 0; i < numPolys; i++) {
            JsonNode polyCoords = coordsNode.get(i);
            if (polyCoords == null || polyCoords.isNull() || !polyCoords.isArray()) {
                throw new SerdeException("MultiPolygon member must be an array of rings");
            }
            polygons[i] = parsePolygon(polyCoords, vertexCount, strictLonBounds);
        }
        return geometryFactory.createMultiPolygon(polygons);
    }

    private LinearRing parseLinearRing(JsonNode ringNode, int[] vertexCount, boolean strictLonBounds) throws IOException {
        int size = ringNode.size();
        if (size > MAX_VERTICES) {
            throw new SerdeException("Linear ring exceeds maximum allowed vertex count of " + MAX_VERTICES);
        }
        List<Coordinate> coords = new ArrayList<>(size + 1);
        for (int i = 0; i < size; i++) {
            JsonNode coordNode = ringNode.get(i);
            if (coordNode == null || coordNode.isNull()) {
                throw new SerdeException("Null coordinate in ring");
            }
            coords.add(parseCoordinate(coordNode, vertexCount, strictLonBounds));
        }
        if (!coords.isEmpty() && !coords.get(0).equals2D(coords.get(coords.size() - 1))) {
            coords.add(new Coordinate(coords.get(0)));
        }
        if (coords.size() < 4) {
            throw new SerdeException("Linear ring must have at least 4 coordinates (including closed point), found: " + coords.size());
        }
        return geometryFactory.createLinearRing(coords.toArray(new Coordinate[0]));
    }

    private Coordinate parseCoordinate(JsonNode coordNode, int[] vertexCount, boolean strictLonBounds) throws IOException {
        if (++vertexCount[0] > MAX_VERTICES) {
            throw new SerdeException("Geometry exceeds maximum allowed vertex count of " + MAX_VERTICES);
        }
        if (!coordNode.isArray() || coordNode.size() < 2) {
            throw new SerdeException("Coordinate must be an array of at least 2 numbers");
        }
        double x = readFiniteNumber(coordNode.get(0), "Coordinate elements must be numbers", "Coordinate elements must be finite numbers");
        double y = readFiniteNumber(coordNode.get(1), "Coordinate elements must be numbers", "Coordinate elements must be finite numbers");
        double maxLon = strictLonBounds ? 180.0 : 180.001;
        if (x < -maxLon || x > maxLon || y < -90.0 || y > 90.0) {
            throw new SerdeException("Coordinate out of WGS84 bounds: [" + x + ", " + y + "]");
        }
        Double z = parseOptionalElevation(coordNode);
        return z != null ? new Coordinate(x, y, z) : new Coordinate(x, y);
    }

    private Double parseOptionalElevation(JsonNode coordNode) throws SerdeException {
        if (coordNode.size() < 3) {
            return null;
        }
        JsonNode zNode = coordNode.get(2);
        if (zNode == null || zNode.isNull()) {
            return null;
        }
        return readFiniteNumber(zNode, "Coordinate elevation must be a number", "Coordinate elevation must be a finite number");
    }

    private double readFiniteNumber(JsonNode node, String nonNumberMsg, String nonFiniteMsg) throws SerdeException {
        if (node == null || node.isNull() || !node.isNumber()) {
            throw new SerdeException(nonNumberMsg);
        }
        double value = node.getDoubleValue();
        if (!Double.isFinite(value)) {
            throw new SerdeException(nonFiniteMsg);
        }
        return value;
    }
}
