package lol.pbu.kaiju.model;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.data.model.runtime.convert.AttributeConverter;
import jakarta.inject.Singleton;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

@Singleton
public class JtsPolygonConverter implements AttributeConverter<Geometry, PGobject> {

    private static final Logger LOG = LoggerFactory.getLogger(JtsPolygonConverter.class);

    @Override
    public PGobject convertToPersistedValue(Geometry entityValue, ConversionContext context) {
        if (entityValue == null) {
            return null;
        }
        try {
            // Include SRID in WKB
            WKBWriter writer = new WKBWriter(2, true);
            byte[] wkb = writer.write(entityValue);
            String hexWkb = WKBWriter.toHex(wkb);

            PGobject pgObject = createPGobject();
            pgObject.setType("geography");
            pgObject.setValue(hexWkb);
            return pgObject;
        } catch (SQLException e) {
            LOG.error("Failed to convert Geometry to PGobject geography: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Failed to serialize Geometry to PGobject geography", e);
        }
    }

    protected PGobject createPGobject() {
        return new PGobject();
    }

    @Override
    public Geometry convertToEntityValue(PGobject persistedValue, ConversionContext context) {
        if (persistedValue == null || persistedValue.getValue() == null) {
            return null;
        }
        try {
            WKBReader reader = new WKBReader();
            byte[] bytes = WKBReader.hexToBytes(persistedValue.getValue());
            Geometry geom = reader.read(bytes);
            if (geom.getSRID() == 0) {
                geom.setSRID(4326);
            }
            return geom;
        } catch (ParseException e) {
            LOG.error("Failed to parse Polygon from WKB: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Failed to parse Polygon from WKB", e);
        }
    }
}
