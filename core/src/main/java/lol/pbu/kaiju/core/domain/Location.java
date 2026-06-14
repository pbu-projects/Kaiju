package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.core.model.JtsPointConverter;
import org.locationtech.jts.geom.Point;

import java.util.List;
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
        Point geom,

        @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "id.locationId")
        List<OrganizationLocation> organizationLocations
) {
    public Location(UUID id, String name, String addressLine, String city, String stateProvince, String postalCode, String countryCode, Point geom) {
        this(id, name, addressLine, city, stateProvince, postalCode, countryCode, geom, List.of());
    }
}
