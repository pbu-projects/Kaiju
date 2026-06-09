package lol.pbu.kaiju.core.model;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.TypeConverter;
import jakarta.inject.Singleton;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.postgresql.util.PGobject;

import java.util.Optional;

@Singleton
public class PgObjectToPointConverter implements TypeConverter<PGobject, Point> {

    @Override
    public Optional<Point> convert(PGobject object, Class<Point> targetType, ConversionContext context) {
        if (object == null || object.getValue() == null) {
            return Optional.empty();
        }

        try {
            WKBReader reader = new WKBReader();
            byte[] bytes = WKBReader.hexToBytes(object.getValue());
            Point point = (Point) reader.read(bytes);

            if (point.getSRID() == 0) {
                point.setSRID(4326);
            }

            return Optional.of(point);
        } catch (ParseException e) {
            context.reject(e);
            return Optional.empty();
        }
    }
}
