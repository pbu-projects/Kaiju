package lol.pbu.kaiju.converter

import io.micronaut.core.convert.ConversionContext
import org.postgresql.util.PGobject
import spock.lang.Specification

import java.sql.SQLException

class JsonbAttributeConverterSpec extends Specification {

    JsonbAttributeConverter converter = new JsonbAttributeConverter()

    def "should convert JSON string to PGobject with jsonb type"() {
        given: "a valid json string"
        String json = '{"name":"Denver","vertexCount":42}'

        when: "converting to persisted value"
        PGobject pgObject = converter.convertToPersistedValue(json, ConversionContext.DEFAULT)

        then: "a PGobject with jsonb type and matching value is returned"
        verifyAll(pgObject) {
            type == "jsonb"
            value == json
        }
    }

    def "should return null when converting null string to persisted value"() {
        when: "converting null string"
        PGobject result = converter.convertToPersistedValue(null, ConversionContext.DEFAULT)

        then: "result is null"
        result == null
    }

    def "should convert PGobject to JSON string"() {
        given: "a PGobject containing JSON"
        PGobject pgObject = new PGobject()
        pgObject.setType("jsonb")
        pgObject.setValue('{"status":"active"}')

        when: "converting to entity value"
        String result = converter.convertToEntityValue(pgObject, ConversionContext.DEFAULT)

        then: "the string value matches"
        result == '{"status":"active"}'
    }

    def "should return null when converting null PGobject to entity value"() {
        when: "converting null PGobject"
        String result = converter.convertToEntityValue(null, ConversionContext.DEFAULT)

        then: "result is null"
        result == null
    }

    def "should reject context and return null when PGobject throws SQLException"() {
        given: "a converter that throws SQLException on setValue"
        JsonbAttributeConverter throwingConverter = new JsonbAttributeConverter() {
            @Override
            protected PGobject createPGobject() {
                return new PGobject() {
                    @Override
                    void setValue(String value) throws SQLException {
                        throw new SQLException("Simulated write error")
                    }
                }
            }
        }
        ConversionContext context = Mock(ConversionContext)

        when: "converting to persisted value"
        PGobject result = throwingConverter.convertToPersistedValue('{"key":"value"}', context)

        then: "context rejects the error and returns null"
        1 * context.reject(_ as SQLException)
        result == null
    }
}
