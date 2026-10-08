package lol.pbu.kaiju.serde;

import io.micronaut.core.type.Argument;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serializer;
import io.micronaut.serde.Serializer.EncoderContext;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Point;

import java.io.IOException;

@Singleton
public class JtsPointSerde implements Serializer<Point> {

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
}
