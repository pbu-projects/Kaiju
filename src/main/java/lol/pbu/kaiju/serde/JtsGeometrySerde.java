package lol.pbu.kaiju.serde;

import io.micronaut.core.type.Argument;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serializer;
import io.micronaut.serde.Serializer.EncoderContext;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;

import static io.micronaut.core.type.Argument.OBJECT_ARGUMENT;

@Singleton
public class JtsGeometrySerde implements Serializer<Geometry> {

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
        if (value instanceof Polygon polygon) {
            writePolygon(objEncoder, polygon);
        } else if (value instanceof MultiPolygon multiPolygon) {
            writeMultiPolygon(objEncoder, multiPolygon);
        } else if (value instanceof Point point) {
            writePoint(objEncoder, point);
        } else {
            objEncoder.encodeKey("type");
            objEncoder.encodeString(value.getGeometryType());
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
            Encoder coordEncoder = ringEncoder.encodeArray(OBJECT_ARGUMENT);
            coordEncoder.encodeDouble(coord.getX());
            coordEncoder.encodeDouble(coord.getY());
            coordEncoder.finishStructure();
        }
        ringEncoder.finishStructure();
    }

    private void writePoint(Encoder encoder, Point point) throws IOException {
        encoder.encodeKey("type");
        encoder.encodeString("Point");

        encoder.encodeKey("coordinates");
        Encoder coordEncoder = encoder.encodeArray(OBJECT_ARGUMENT);
        coordEncoder.encodeDouble(point.getX());
        coordEncoder.encodeDouble(point.getY());
        coordEncoder.finishStructure();
    }
}
