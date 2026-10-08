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
import org.locationtech.jts.geom.Point;

import java.io.IOException;

@Singleton
public class JtsPointSerde implements Serde<Point> {

    private final JtsGeometrySerde geometrySerde;

    public JtsPointSerde(JtsGeometrySerde geometrySerde) {
        this.geometrySerde = geometrySerde;
    }

    @Override
    public void serialize(
            @NonNull Encoder encoder,
            @NonNull EncoderContext context,
            @NonNull Argument<? extends Point> type,
            @Nullable Point value
    ) throws IOException {
        geometrySerde.serialize(encoder, context, type, value);
    }

    @Override
    public Point deserialize(
            @NonNull Decoder decoder,
            @NonNull DecoderContext context,
            @NonNull Argument<? super Point> type
    ) throws IOException {
        Geometry geom = geometrySerde.deserialize(decoder, context, Argument.of(Geometry.class));
        if (geom == null) {
            return null;
        }
        if (geom instanceof Point point) {
            return point;
        }
        throw new SerdeException("Expected Point geometry, but found " + geom.getGeometryType());
    }
}
