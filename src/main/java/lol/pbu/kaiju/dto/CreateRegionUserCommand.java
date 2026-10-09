package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.RegionUserRole;

import java.util.UUID;

@Serdeable
public record CreateRegionUserCommand(
        @NotNull(message = "User ID is required.")
        UUID userId,

        @NotNull(message = "Region ID is required.")
        UUID regionId,

        @NotNull(message = "Region user role is required.")
        RegionUserRole role
) {}
