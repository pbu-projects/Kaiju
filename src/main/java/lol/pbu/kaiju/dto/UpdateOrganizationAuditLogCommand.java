package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

@Serdeable
public record UpdateOrganizationAuditLogCommand(
        @NotBlank(message = "Previous status is required.")
        String previousStatus,

        @NotBlank(message = "New status is required.")
        String newStatus,

        @Nullable
        String reason
) {}
