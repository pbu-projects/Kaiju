package lol.pbu.kaiju.serde;

import io.micronaut.core.type.Argument;
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Deserializer.DecoderContext;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serde;
import io.micronaut.serde.Serializer.EncoderContext;
import io.micronaut.serde.exceptions.SerdeException;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;

/**
 * Typed Micronaut Serde for JTS {@link Polygon} objects.
 *
 * <p>Delegates to {@link JtsGeometrySerde} to serialize and deserialize GeoJSON Polygons over HTTP.
 * Domain entities bound to Polygon persist to PostGIS {@code geography(Polygon, 4326)} for geodesic accuracy.</p>
 */
@Singleton
public class JtsPolygonSerde implements Serde<Polygon> {

    private final JtsGeometrySerde geometrySerde;

    public JtsPolygonSerde(JtsGeometrySerde geometrySerde) {
        this.geometrySerde = geometrySerde;
    }

    @Override
    public void serialize(
            @NonNull Encoder encoder,
            @NonNull EncoderContext context,
            @NonNull Argument<? extends Polygon> type,
            @Nullable Polygon value
    ) throws IOException {
        geometrySerde.serialize(encoder, context, type, value);
    }

    @Override
    public Polygon deserialize(
            @NonNull Decoder decoder,
            @NonNull DecoderContext context,
            @NonNull Argument<? super Polygon> type
    ) throws IOException {
        Geometry geom = geometrySerde.deserialize(decoder, context, Argument.of(Geometry.class));
        if (geom == null) {
            return null;
        }
        if (geom instanceof Polygon polygon) {
            return polygon;
        }
        throw new SerdeException("Expected Polygon geometry, but found " + geom.getGeometryType());
    }
}
