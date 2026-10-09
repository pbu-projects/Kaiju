package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Serdeable
public record UpdateAdministrativeRegionCommand(
        @NotBlank(message = "Administrative region name is required.")
        @Size(min = 1, max = 255, message = "Administrative region name must be between 1 and 255 characters.")
        String name,

        @Nullable
        UUID parentRegionId,

        @NotNull(message = "Region coordinates are required.")
        @Size(min = 3, message = "A region polygon must contain at least 3 coordinates.")
        @Valid
        List<CoordinateDto> coordinates
) {}
