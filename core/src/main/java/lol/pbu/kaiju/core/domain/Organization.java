package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinColumn;
import io.micronaut.data.annotation.sql.JoinTable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.core.model.VerificationStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.ONE_TO_MANY;

@MappedEntity("organizations")
public record Organization(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "Organization name is required.")
        @Size(min = 1, max = 255, message = "Organization name must be between 1 and 255 characters.")
        String name,

        @Nullable
        @Size(min = 1, max = 255, message = "Organization website URL must be between 1 and 255 characters.")
        String websiteUrl,

        @Nullable UUID parentId,

        @NotNull(message = "isPublic is required.")
        Boolean isPublic,

        @NotNull(message = "Verification status is required.")
        VerificationStatus verificationStatus,

        @Nullable
        OffsetDateTime verificationExpiresAt,

        @Relation(value = ONE_TO_MANY, mappedBy = "id.organizationId")
        @JoinTable(
                name = "organization_locations",
                joinColumns = @JoinColumn(name = "organization_id"),
                inverseJoinColumns = @JoinColumn(name = "location_id")
        )
        List<Location> locations
) {
    /**
     * Instead of using setters, this method gives the opportunity to take an existing organization ID and assign that to
     * the organization properties in this organization record.
     *
     * @param newId The UUID to assign to the organization.
     * @return A new {@link Organization} with the given ID.
     */
    public Organization withId(@NotNull UUID newId) {
        return new Organization(
                newId,
                this.name(),
                this.websiteUrl(),
                this.parentId(),
                this.isPublic(),
                this.verificationStatus(),
                this.verificationExpiresAt(),
                this.locations()
        );
    }
}
