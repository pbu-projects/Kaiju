package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.AuditAction;

import java.util.UUID;

@Serdeable
public record CreateProjectAuditLogCommand(
        @NotNull(message = "Project ID is required.")
        UUID projectId,

        @NotNull(message = "Actor ID is required.")
        UUID actorId,

        @NotNull(message = "Audit log action is required.")
        AuditAction action
) {}
