package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.AuditAction;

@Serdeable
public record UpdateProjectAuditLogCommand(
        @NotNull(message = "Audit log action is required.")
        AuditAction action
) {}
