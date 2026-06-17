package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.TypeDef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.core.model.JtsPointConverter;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

import static io.micronaut.data.model.DataType.OBJECT;

@MappedEntity("locations")
public record Location(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "Name is required.")
        @Size(min = 1, max = 255, message = "Name must be between 1 and 255 characters.")
        String name,

        @NotBlank(message = "Address line is required.")
        @Size(min = 1, max = 255, message = "Address line must be between 1 and 255 characters.")
        String addressLine,

        @NotBlank(message = "City is required.")
        @Size(min = 1, max = 100, message = "City must be between 1 and 100 characters.")
        String city,

        @Nullable
        @Size(min = 1, max = 100, message = "State/Province must be between 1 and 100 characters.")
        String stateProvince,

        @Nullable
        @Size(min = 1, max = 20, message = "Postal code must be between 1 and 20 characters.")
        String postalCode,

        @NotBlank(message = "Country code is required.")
        @Size(min = 2, max = 2, message = "Country code must be 2 characters.")
        String countryCode,

        @TypeDef(type = OBJECT, converter = JtsPointConverter.class)
        Point geom
) {
}
