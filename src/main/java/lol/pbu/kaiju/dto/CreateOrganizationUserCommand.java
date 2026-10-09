package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.OrganizationUserRole;

import java.util.UUID;

@Serdeable
public record CreateOrganizationUserCommand(
        @NotNull(message = "User ID is required.")
        UUID userId,

        @NotNull(message = "Organization ID is required.")
        UUID organizationId,

        @NotNull(message = "Organization user role is required.")
        OrganizationUserRole role
) {}
