package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Serdeable
public record CreateOrganizationAuditLogCommand(
        @NotNull(message = "Organization ID is required.")
        UUID organizationId,

        @NotNull(message = "Actor ID is required.")
        UUID actorId,

        @NotBlank(message = "Previous status is required.")
        String previousStatus,

        @NotBlank(message = "New status is required.")
        String newStatus,

        @Nullable
        String reason
) {}
