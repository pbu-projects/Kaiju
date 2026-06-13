package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.core.model.JtsPointConverter;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

@MappedEntity("locations")
public record Location(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank
        String name,

        @NotBlank
        String addressLine,

        @NotBlank
        String city,

        @Nullable
        String stateProvince,

        @Nullable
        String postalCode,

        @NotBlank
        @Size(min = 2, max = 2)
        String countryCode,

        @TypeDef(type = DataType.OBJECT, converter = JtsPointConverter.class)
        Point geom
) {}
