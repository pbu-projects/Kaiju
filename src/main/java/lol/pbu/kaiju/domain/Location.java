package lol.pbu.kaiju.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.JtsPointConverter;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

import static io.micronaut.data.model.DataType.OBJECT;

@Serdeable
@MappedEntity("locations")
public record Location(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "Location name is required.")
        @Size(min = 1, max = 255, message = "Location name must be between 1 and 255 characters.")
        String name,

        @NotBlank(message = "Location address line is required.")
        @Size(min = 1, max = 255, message = "Location address line must be between 1 and 255 characters.")
        String addressLine,

        @NotBlank(message = "Location city is required.")
        @Size(min = 1, max = 100, message = "Location city must be between 1 and 100 characters.")
        String city,

        @Nullable
        @Size(min = 1, max = 100, message = "Location state/province must be between 1 and 100 characters.")
        String stateProvince,

        @Nullable
        @Size(min = 1, max = 20, message = "Location postal code must be between 1 and 20 characters.")
        String postalCode,

        @NotBlank(message = "Location country code is required.")
        @Size(min = 2, max = 2, message = "Location country code must be 2 characters.")
        String countryCode,

        @TypeDef(type = OBJECT, converter = JtsPointConverter.class)
        Point geom,

        @Nullable
        @Size(min = 1, max = 100, message = "Location timezone must be between 1 and 100 characters.")
        String timezone
) {
    public Location(
            UUID id,
            String name,
            String addressLine,
            String city,
            String stateProvince,
            String postalCode,
            String countryCode,
            Point geom
    ) {
        this(id, name, addressLine, city, stateProvince, postalCode, countryCode, geom, null);
    }

    public static Location forId(@NotNull UUID id) {
        return new Location(id, null, null, null, null, null, null, null, null);
    }

    public static Location forCreation(
            @NotNull String name,
            @NotNull String addressLine,
            @NotNull String city,
            @Nullable String stateProvince,
            @Nullable String postalCode,
            @NotNull String countryCode,
            @Nullable Point geom
    ) {
        return forCreation(name, addressLine, city, stateProvince, postalCode, countryCode, geom, null);
    }

    public static Location forCreation(
            @NotNull String name,
            @NotNull String addressLine,
            @NotNull String city,
            @Nullable String stateProvince,
            @Nullable String postalCode,
            @NotNull String countryCode,
            @Nullable Point geom,
            @Nullable String timezone
    ) {
        return new Location(null, name, addressLine, city, stateProvince, postalCode, countryCode, geom, timezone);
    }

    /**
     * Instead of using setters, this method gives the opportunity to take an existing location ID and assign that to
     * the location properties in this location record.
     *
     * @param newId The UUID to assign to the location.
     * @return A new {@link Location} with the given ID.
     */
    public Location withId(@NotNull UUID newId) {
        return new Location(
                newId,
                this.name(),
                this.addressLine(),
                this.city(),
                this.stateProvince(),
                this.postalCode(),
                this.countryCode(),
                this.geom(),
                this.timezone()
        );
    }

    public Location withUpdatedDetails(
            @NotNull String newName,
            @NotNull String newAddressLine,
            @NotNull String newCity,
            @Nullable String newStateProvince,
            @Nullable String newPostalCode,
            @NotNull String newCountryCode,
            @Nullable Point newGeom,
            @Nullable String newTimezone
    ) {
        return new Location(
                this.id(),
                newName,
                newAddressLine,
                newCity,
                newStateProvince,
                newPostalCode,
                newCountryCode,
                newGeom,
                newTimezone
        );
    }

    public Location withTimezone(@Nullable String newTimezone) {
        return new Location(
                this.id(),
                this.name(),
                this.addressLine(),
                this.city(),
                this.stateProvince(),
                this.postalCode(),
                this.countryCode(),
                this.geom(),
                newTimezone
        );
    }
}
