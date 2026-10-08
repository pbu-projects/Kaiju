package lol.pbu.kaiju.serde;

import io.micronaut.core.type.Argument;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serializer;
import io.micronaut.serde.Serializer.EncoderContext;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;

@Singleton
public class JtsPolygonSerde implements Serializer<Polygon> {

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
}
