package lol.pbu.kaiju.core.model;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.TypeConverter;
import jakarta.inject.Singleton;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.util.Optional;

@Singleton
public class PolygonToPgObjectConverter implements TypeConverter<Polygon, PGobject> {

    @Override
    public Optional<PGobject> convert(Polygon object, Class<PGobject> targetType, ConversionContext context) {
        if (object == null) {
            return Optional.empty();
        }
        try {
            WKBWriter writer = new WKBWriter(2, true);
            byte[] wkb = writer.write(object);
            String hexWkb = WKBWriter.toHex(wkb);

            PGobject pgObject = new PGobject();
            pgObject.setType("geography");
            pgObject.setValue(hexWkb);
            return Optional.of(pgObject);
        } catch (SQLException e) {
            context.reject(e);
            return Optional.empty();
        }
    }
}
