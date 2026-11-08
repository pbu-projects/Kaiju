package lol.pbu.kaiju.converter;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.data.model.runtime.convert.AttributeConverter;
import jakarta.inject.Singleton;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

@Singleton
public class JsonbAttributeConverter implements AttributeConverter<String, PGobject> {

    @Override
    public PGobject convertToPersistedValue(String entityValue, ConversionContext context) {
        if (entityValue == null) {
            return null;
        }
        PGobject pg = createPGobject();
        pg.setType("jsonb");
        try {
            pg.setValue(entityValue);
            return pg;
        } catch (SQLException e) {
            context.reject(e);
            return null;
        }
    }

    protected PGobject createPGobject() {
        return new PGobject();
    }

    @Override
    public String convertToEntityValue(PGobject persistedValue, ConversionContext context) {
        if (persistedValue == null) {
            return null;
        }
        return persistedValue.getValue();
    }
}
