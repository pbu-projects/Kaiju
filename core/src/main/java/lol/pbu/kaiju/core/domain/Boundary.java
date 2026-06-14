package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;
import jakarta.validation.constraints.NotBlank;
import lol.pbu.kaiju.core.model.JtsPolygonConverter;
import org.locationtech.jts.geom.Polygon;

import java.util.UUID;

@MappedEntity("boundaries")
public record Boundary(
        @Id @GeneratedValue UUID id,
        @NotBlank String name,
        @TypeDef(type = DataType.OBJECT, converter = JtsPolygonConverter.class)
        Polygon geom
) {}
