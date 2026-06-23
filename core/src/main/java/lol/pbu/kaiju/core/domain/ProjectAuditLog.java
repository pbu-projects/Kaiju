package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.core.model.AuditAction;

import java.time.OffsetDateTime;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_ONE;

@MappedEntity("project_audit_logs")
public record ProjectAuditLog(
        @Id
        @GeneratedValue
        UUID id,
        
        @Relation(MANY_TO_ONE)
        @NotNull(message = "Audit log project is required.")
        Project project,
        
        @Relation(MANY_TO_ONE)
        @NotNull(message = "Audit log actor is required.")
        User actor,
        
        @NotNull(message = "Audit log action is required.")
        AuditAction action,
        OffsetDateTime createdAt
) {}
