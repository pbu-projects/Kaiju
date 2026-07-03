package lol.pbu.kaiju.core.model;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.data.model.runtime.convert.AttributeConverter;
import jakarta.inject.Singleton;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

@Singleton
public class JtsPointConverter implements AttributeConverter<Point, PGobject> {

    @Override
    public PGobject convertToPersistedValue(Point entityValue, ConversionContext context) {
        if (entityValue == null) {
            return null;
        }
        try {
            // Include SRID in WKB (3D=false, SRID=true)
            WKBWriter writer = new WKBWriter(2, true);
            byte[] wkb = writer.write(entityValue);
            String hexWkb = WKBWriter.toHex(wkb);

            PGobject pgObject = createPGobject();
            pgObject.setType("geography");
            pgObject.setValue(hexWkb);
            return pgObject;
        } catch (SQLException e) {
            return null;
        }
    }

    protected PGobject createPGobject() {
        return new PGobject();
    }

    @Override
    public Point convertToEntityValue(PGobject persistedValue, ConversionContext context) {
        if (persistedValue == null || persistedValue.getValue() == null) {
            return null;
        }
        try {
            WKBReader reader = new WKBReader();
            byte[] bytes = WKBReader.hexToBytes(persistedValue.getValue());
            Point point = (Point) reader.read(bytes);
            if (point.getSRID() == 0) {
                point.setSRID(4326);
            }
            return point;
        } catch (ParseException e) {
            return null;
        }
    }
}
