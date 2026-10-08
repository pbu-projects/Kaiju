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
import org.locationtech.jts.geom.MultiPolygon;

import java.io.IOException;

@Singleton
public class JtsMultiPolygonSerde implements Serde<MultiPolygon> {

    private final JtsGeometrySerde geometrySerde;

    public JtsMultiPolygonSerde(JtsGeometrySerde geometrySerde) {
        this.geometrySerde = geometrySerde;
    }

    @Override
    public void serialize(
            @NonNull Encoder encoder,
            @NonNull EncoderContext context,
            @NonNull Argument<? extends MultiPolygon> type,
            @Nullable MultiPolygon value
    ) throws IOException {
        geometrySerde.serialize(encoder, context, type, value);
    }

    @Override
    public MultiPolygon deserialize(
            @NonNull Decoder decoder,
            @NonNull DecoderContext context,
            @NonNull Argument<? super MultiPolygon> type
    ) throws IOException {
        Geometry geom = geometrySerde.deserialize(decoder, context, Argument.of(Geometry.class));
        if (geom == null) {
            return null;
        }
        if (geom instanceof MultiPolygon multiPolygon) {
            return multiPolygon;
        }
        throw new SerdeException("Expected MultiPolygon geometry, but found " + geom.getGeometryType());
    }
}
