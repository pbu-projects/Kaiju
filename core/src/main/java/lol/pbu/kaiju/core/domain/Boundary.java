package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.TypeDef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.core.model.JtsPolygonConverter;
import org.locationtech.jts.geom.Polygon;

import java.util.UUID;

import static io.micronaut.data.model.DataType.OBJECT;

@MappedEntity("boundaries")
public record Boundary(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "Boundary name is required.")
        @Size(min = 1, max = 255, message = "Boundary name must be between 1 and 255 characters.")
        String name,

        @NotNull(message = "Boundary geometry is required.")
        @TypeDef(type = OBJECT, converter = JtsPolygonConverter.class)
        Polygon geom
) {}
