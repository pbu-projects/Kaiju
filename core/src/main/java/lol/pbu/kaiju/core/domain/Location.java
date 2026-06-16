package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.core.model.JtsPointConverter;
import org.locationtech.jts.geom.Point;

import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.ONE_TO_MANY;
import static io.micronaut.data.model.DataType.OBJECT;

@MappedEntity("locations")
public record Location(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "A location name is required")
        @Size(min = 1, max = 80, message = "Location name must be between 1 and 80 characters")
        String name,

        @NotBlank(message = "An address line is required")
        @Size(min = 1, max = 255, message = "Address line must be between 1 and 255 characters")
        String address,

        @NotBlank(message = "A city is required")
        @Size(min = 1, max = 100, message = "City must be between 1 and 100 characters")
        String city,

        @NotBlank(message = "A state province is required")
        @Size(min = 1, max = 100, message = "State province must be between 1 and 100 characters")
        String stateProvince,

        @Nullable
        @Size(min = 1, max = 20, message = "Postal code must be between 1 and 20 characters")
        String postalCode,

        @NotBlank(message = "A country code is required")
        @Size(min = 2, max = 2, message = "Country code must be 2 characters")
        String countryCode,

        @NotBlank(message = "a geometry point is required")
        @TypeDef(type = OBJECT, converter = JtsPointConverter.class)
        Point geom,

        @Relation(value = ONE_TO_MANY, mappedBy = "id.locationId")
        List<OrganizationLocation> organizationLocations
) {
    public Location withId(UUID id) {
        return new Location(id, name, address, city, stateProvince, postalCode, countryCode, geom, organizationLocations);
    }
}
