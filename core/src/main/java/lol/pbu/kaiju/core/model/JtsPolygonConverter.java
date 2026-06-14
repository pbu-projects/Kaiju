package lol.pbu.kaiju.core.model;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.data.model.runtime.convert.AttributeConverter;
import jakarta.inject.Singleton;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

@Singleton
public class JtsPolygonConverter implements AttributeConverter<Polygon, PGobject> {

    @Override
    public PGobject convertToPersistedValue(Polygon entityValue, ConversionContext context) {
        if (entityValue == null) {
            return null;
        }
        try {
            // Include SRID in WKB
            WKBWriter writer = new WKBWriter(2, true);
            byte[] wkb = writer.write(entityValue);
            String hexWkb = WKBWriter.toHex(wkb);

            PGobject pgObject = new PGobject();
            pgObject.setType("geography");
            pgObject.setValue(hexWkb);
            return pgObject;
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public Polygon convertToEntityValue(PGobject persistedValue, ConversionContext context) {
        if (persistedValue == null || persistedValue.getValue() == null) {
            return null;
        }
        try {
            WKBReader reader = new WKBReader();
            byte[] bytes = WKBReader.hexToBytes(persistedValue.getValue());
            Polygon polygon = (Polygon) reader.read(bytes);
            if (polygon.getSRID() == 0) {
                polygon.setSRID(4326);
            }
            return polygon;
        } catch (ParseException e) {
            return null;
        }
    }
}
