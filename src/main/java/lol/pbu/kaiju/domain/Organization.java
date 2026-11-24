package lol.pbu.kaiju.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinColumn;
import io.micronaut.data.annotation.sql.JoinTable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.VerificationStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.ONE_TO_MANY;

@Serdeable
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
    public static Organization forId(@NotNull UUID id) {
        return new Organization(id, null, null, null, true, null, null, null);
    }

    public static Organization forCreation(
            @NotNull String name,
            @Nullable String websiteUrl,
            @Nullable UUID parentId,
            @NotNull Boolean isPublic
    ) {
        return new Organization(
                null,
                name,
                websiteUrl,
                parentId,
                isPublic,
                VerificationStatus.UNVERIFIED,
                null,
                List.of()
        );
    }

    public static Organization forUpdate(
            @NotNull UUID id,
            @NotNull String name,
            @Nullable String websiteUrl,
            @Nullable UUID parentId,
            @NotNull Boolean isPublic
    ) {
        return new Organization(
                id,
                name,
                websiteUrl,
                parentId,
                isPublic,
                null,
                null,
                null
        );
    }

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

    public Organization withCreationDetails(
            @NotNull VerificationStatus newVerificationStatus,
            @NotNull List<Location> newLocations
    ) {
        return new Organization(
                null,
                this.name(),
                this.websiteUrl(),
                this.parentId(),
                this.isPublic(),
                newVerificationStatus,
                null,
                newLocations
        );
    }

    public Organization withUpdatedDetails(
            @NotNull Organization source,
            @Nullable UUID newParentId
    ) {
        return new Organization(
                this.id(),
                source.name(),
                source.websiteUrl(),
                newParentId,
                source.isPublic(),
                this.verificationStatus(),
                this.verificationExpiresAt(),
                this.locations()
        );
    }

    public Organization withVerificationStatus(@NotNull VerificationStatus newStatus) {
        return new Organization(
                this.id(),
                this.name(),
                this.websiteUrl(),
                this.parentId(),
                this.isPublic(),
                newStatus,
                this.verificationExpiresAt(),
                this.locations()
        );
    }
}
