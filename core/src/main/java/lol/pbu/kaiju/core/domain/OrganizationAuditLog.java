package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_ONE;

@MappedEntity("organization_audit_logs")
public record OrganizationAuditLog(
        @Id
        @GeneratedValue
        UUID id,

        @Relation(MANY_TO_ONE)
        @NotNull(message = "Audit log organization is required.")
        Organization organization,

        @Relation(MANY_TO_ONE)
        @MappedProperty("actor_id")
        @NotNull(message = "Audit log actor is required.")
        User actor,

        @NotBlank(message = "Previous status is required.")
        String previousStatus,

        @NotBlank(message = "New status is required.")
        String newStatus,

        @Nullable
        String reason,

        OffsetDateTime createdAt
) {
    /**
     * Instead of using setters, this method gives the opportunity to take an existing log ID and assign that to
     * the properties in this record.
     *
     * @param newId The UUID to assign.
     * @return A new {@link OrganizationAuditLog} with the given ID.
     */
    public OrganizationAuditLog withId(@NotNull UUID newId) {
        return new OrganizationAuditLog(
                newId,
                this.organization(),
                this.actor(),
                this.previousStatus(),
                this.newStatus(),
                this.reason(),
                this.createdAt()
        );
    }
}
