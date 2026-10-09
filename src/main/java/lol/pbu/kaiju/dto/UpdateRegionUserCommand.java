package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.RegionUserRole;

@Serdeable
public record UpdateRegionUserCommand(
        @NotNull(message = "Region user role is required.")
        RegionUserRole role
) {}
