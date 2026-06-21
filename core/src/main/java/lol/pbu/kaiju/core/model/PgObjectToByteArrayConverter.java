package lol.pbu.kaiju.core.model;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.TypeConverter;
import jakarta.inject.Singleton;
import org.locationtech.jts.io.WKBReader;
import org.postgresql.util.PGobject;

import java.util.Optional;

@Singleton
public class PgObjectToByteArrayConverter implements TypeConverter<PGobject, byte[]> {

    @Override
    public Optional<byte[]> convert(PGobject object, Class<byte[]> targetType, ConversionContext context) {
        if (object == null || object.getValue() == null) {
            return Optional.empty();
        }
        return Optional.of(WKBReader.hexToBytes(object.getValue()));
    }
}
