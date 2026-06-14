package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.core.model.AuditAction;

import java.time.OffsetDateTime;
import java.util.UUID;

@MappedEntity("project_audit_logs")
public record ProjectAuditLog(
        @Id @GeneratedValue UUID id,
        
        @Relation(Relation.Kind.MANY_TO_ONE)
        @NotNull Project project,
        
        @Relation(Relation.Kind.MANY_TO_ONE)
        @NotNull User actor,
        
        @NotNull AuditAction action,
        OffsetDateTime createdAt
) {}
