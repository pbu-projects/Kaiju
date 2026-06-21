package lol.pbu.kaiju.core.model;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.TypeConverter;
import jakarta.inject.Singleton;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.postgresql.util.PGobject;

import java.util.Optional;

@Singleton
public class PgObjectToPolygonConverter implements TypeConverter<PGobject, Polygon> {

    @Override
    public Optional<Polygon> convert(PGobject object, Class<Polygon> targetType, ConversionContext context) {
        if (object == null || object.getValue() == null) {
            return Optional.empty();
        }

        try {
            WKBReader reader = new WKBReader();
            byte[] bytes = WKBReader.hexToBytes(object.getValue());
            Polygon polygon = (Polygon) reader.read(bytes);

            if (polygon.getSRID() == 0) {
                polygon.setSRID(4326);
            }

            return Optional.of(polygon);
        } catch (ParseException e) {
            return Optional.empty();
        }
    }
}
