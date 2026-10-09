package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Serdeable
public record UpdateLocationCommand(
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

        @NotNull(message = "Longitude is required.")
        @Min(value = -180, message = "Longitude must be greater than or equal to -180.")
        @Max(value = 180, message = "Longitude must be less than or equal to 180.")
        Double longitude,

        @NotNull(message = "Latitude is required.")
        @Min(value = -90, message = "Latitude must be greater than or equal to -90.")
        @Max(value = 90, message = "Latitude must be less than or equal to 90.")
        Double latitude
) {}
