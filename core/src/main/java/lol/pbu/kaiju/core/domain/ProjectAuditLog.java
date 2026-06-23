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
) {
    /**
     * Instead of using setters, this method gives the opportunity to take an existing audit log ID and assign that to
     * the audit log properties in this audit log record.
     *
     * @param newId The UUID to assign to the audit log.
     * @return A new {@link ProjectAuditLog} with the given ID.
     */
    public ProjectAuditLog withId(@NotNull UUID newId) {
        return new ProjectAuditLog(
                newId,
                this.project(),
                this.actor(),
                this.action(),
                this.createdAt()
        );
    }
}
