package lol.pbu.kaiju.serde;

import io.micronaut.core.type.Argument;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Deserializer.DecoderContext;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serde;
import io.micronaut.serde.Serializer.EncoderContext;
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

        if (!(value instanceof Polygon || value instanceof MultiPolygon || value instanceof Point)) {
            throw new SerdeException("Unsupported geometry type for serialization: " + value.getGeometryType());
        }

        Encoder objEncoder = encoder.encodeObject(type);
        if (value instanceof Polygon polygon) {
            writePolygon(objEncoder, polygon);
        } else if (value instanceof MultiPolygon multiPolygon) {
            writeMultiPolygon(objEncoder, multiPolygon);
        } else if (value instanceof Point point) {
            writePoint(objEncoder, point);
        }
        objEncoder.finishStructure();
    }

    private void writePolygon(Encoder encoder, Polygon polygon) throws IOException {
        encoder.encodeKey("type");
        encoder.encodeString("Polygon");

        encoder.encodeKey("coordinates");
        Encoder ringsEncoder = encoder.encodeArray(OBJECT_ARGUMENT);
        writeRing(ringsEncoder, polygon.getExteriorRing());
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            writeRing(ringsEncoder, polygon.getInteriorRingN(i));
        }
        ringsEncoder.finishStructure();
    }

    private void writeMultiPolygon(Encoder encoder, MultiPolygon multiPolygon) throws IOException {
        encoder.encodeKey("type");
        encoder.encodeString("MultiPolygon");

        encoder.encodeKey("coordinates");
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
        encoder.encodeKey("type");
        encoder.encodeString("Point");

        encoder.encodeKey("coordinates");
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
        return deserializeFromNode(node);
    }

    public Geometry deserializeFromNode(@Nullable JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new SerdeException("GeoJSON geometry must be a JSON object");
        }
        JsonNode typeNode = node.get("type");
        if (typeNode == null || !typeNode.isString()) {
            throw new SerdeException("Missing or invalid 'type' field in GeoJSON geometry");
        }
        String geomType = typeNode.getStringValue();
        JsonNode coordsNode = node.get("coordinates");
        if (coordsNode == null || !coordsNode.isArray()) {
            throw new SerdeException("Missing or invalid 'coordinates' field in GeoJSON geometry");
        }

        if ("Polygon".equalsIgnoreCase(geomType)) {
            return parsePolygon(coordsNode);
        } else if ("MultiPolygon".equalsIgnoreCase(geomType)) {
            return parseMultiPolygon(coordsNode);
        } else if ("Point".equalsIgnoreCase(geomType)) {
            return parsePoint(coordsNode);
        } else {
            throw new SerdeException("Unsupported geometry type: " + geomType);
        }
    }

    private Point parsePoint(JsonNode coordsNode) throws IOException {
        Coordinate coord = parseCoordinate(coordsNode);
        return geometryFactory.createPoint(coord);
    }

    private Polygon parsePolygon(JsonNode coordsNode) throws IOException {
        if (coordsNode.size() == 0) {
            throw new SerdeException("Polygon coordinates array must not be empty");
        }
        JsonNode shellNode = coordsNode.get(0);
        if (shellNode == null || !shellNode.isArray()) {
            throw new SerdeException("Polygon exterior ring must be an array of coordinates");
        }
        LinearRing shell = parseLinearRing(shellNode);
        int numHoles = coordsNode.size() - 1;
        LinearRing[] holes = new LinearRing[numHoles];
        for (int i = 0; i < numHoles; i++) {
            JsonNode holeNode = coordsNode.get(i + 1);
            if (holeNode == null || !holeNode.isArray()) {
                throw new SerdeException("Polygon interior ring must be an array of coordinates");
            }
            holes[i] = parseLinearRing(holeNode);
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    private MultiPolygon parseMultiPolygon(JsonNode coordsNode) throws IOException {
        int numPolys = coordsNode.size();
        Polygon[] polygons = new Polygon[numPolys];
        for (int i = 0; i < numPolys; i++) {
            JsonNode polyCoords = coordsNode.get(i);
            if (polyCoords == null || !polyCoords.isArray()) {
                throw new SerdeException("MultiPolygon member must be an array of rings");
            }
            polygons[i] = parsePolygon(polyCoords);
        }
        return geometryFactory.createMultiPolygon(polygons);
    }

    private LinearRing parseLinearRing(JsonNode ringNode) throws IOException {
        int size = ringNode.size();
        List<Coordinate> coords = new ArrayList<>(size + 1);
        for (int i = 0; i < size; i++) {
            JsonNode coordNode = ringNode.get(i);
            if (coordNode == null) {
                throw new SerdeException("Null coordinate in ring");
            }
            coords.add(parseCoordinate(coordNode));
        }
        if (!coords.isEmpty() && !coords.get(0).equals2D(coords.get(coords.size() - 1))) {
            coords.add(new Coordinate(coords.get(0)));
        }
        if (coords.size() < 4) {
            throw new SerdeException("Linear ring must have at least 4 coordinates (including closed point), found: " + coords.size());
        }
        return geometryFactory.createLinearRing(coords.toArray(new Coordinate[0]));
    }

    private Coordinate parseCoordinate(JsonNode coordNode) throws IOException {
        if (!coordNode.isArray() || coordNode.size() < 2) {
            throw new SerdeException("Coordinate must be an array of at least 2 numbers");
        }
        JsonNode xNode = coordNode.get(0);
        JsonNode yNode = coordNode.get(1);
        if (xNode == null || !xNode.isNumber() || yNode == null || !yNode.isNumber()) {
            throw new SerdeException("Coordinate elements must be numbers");
        }
        double x = xNode.getDoubleValue();
        double y = yNode.getDoubleValue();
        if (coordNode.size() >= 3 && coordNode.get(2) != null && coordNode.get(2).isNumber()) {
            return new Coordinate(x, y, coordNode.get(2).getDoubleValue());
        }
        return new Coordinate(x, y);
    }
}
