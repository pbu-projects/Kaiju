package lol.pbu.kaiju.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinTable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.model.ProjectType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_MANY;
import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_ONE;

@Serdeable
@MappedEntity("projects")
public record Project(
        @Id
        @GeneratedValue
        UUID id,
        
        @Relation(MANY_TO_ONE)
        @NotNull(message = "Project organization is required.")
        Organization organization,

        @Relation(MANY_TO_ONE)
        @MappedProperty("managing_region_id")
        @Nullable AdministrativeRegion managingRegion,
        
        @NotBlank(message = "Project title is required.")
        @Size(min = 1, max = 255, message = "Project title must be between 1 and 255 characters.")
        String title,

        @NotBlank(message = "Project description is required.")
        String description,

        @NotNull(message = "Project type is required.")
        ProjectType projectType,

        @NotNull(message = "Project status is required.")
        ProjectStatus status,

        OffsetDateTime createdAt,
        @Nullable OffsetDateTime deletedAt,
        
        @Relation(MANY_TO_ONE)
        @MappedProperty("deleted_by")
        @Nullable User deletedBy,

        @Relation(MANY_TO_MANY)
        @JoinTable(name = "project_locations")
        List<Location> locations, //can be empty if the project is a virtual one

        @Relation(MANY_TO_MANY)
        @JoinTable(name = "project_boundaries")
        List<Boundary> boundaries
) {
    public static Project forId(@NotNull UUID id) {
        return new Project(id, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static Project forCreation(
            @Nullable Organization organization,
            @Nullable AdministrativeRegion managingRegion,
            @NotNull String title,
            @NotNull String description,
            @NotNull ProjectType projectType,
            @Nullable ProjectStatus status,
            @NotNull List<Location> locations,
            @NotNull List<Boundary> boundaries
    ) {
        return new Project(
                null,
                organization,
                managingRegion,
                title,
                description,
                projectType,
                status,
                null,
                null,
                null,
                locations,
                boundaries
        );
    }

    public static Project forUpdate(
            @NotNull UUID id,
            @Nullable Organization organization,
            @Nullable AdministrativeRegion managingRegion,
            @NotNull String title,
            @NotNull String description,
            @NotNull ProjectType projectType,
            @NotNull List<Location> locations,
            @NotNull List<Boundary> boundaries
    ) {
        return new Project(
                id,
                organization,
                managingRegion,
                title,
                description,
                projectType,
                null,
                null,
                null,
                null,
                locations,
                boundaries
        );
    }

    /**
     * Instead of using setters, this method gives the opportunity to take an existing project ID and assign that to
     * the project properties in this project record.
     * @param newId The UUID to assign to the project.
     * @return A new {@link Project} with the given ID.
     */
    public Project withId(@NotNull UUID newId) {
        return new Project(
                newId,
                this.organization(),
                this.managingRegion(),
                this.title(),
                this.description(),
                this.projectType(),
                this.status(),
                this.createdAt(),
                this.deletedAt(),
                this.deletedBy(),
                this.locations(),
                this.boundaries()
        );
    }

    public Project withStatus(@NotNull ProjectStatus newStatus) {
        return new Project(
                this.id(),
                this.organization(),
                this.managingRegion(),
                this.title(),
                this.description(),
                this.projectType(),
                newStatus,
                this.createdAt(),
                this.deletedAt(),
                this.deletedBy(),
                this.locations(),
                this.boundaries()
        );
    }

    public Project withCreationDetails(
            @NotNull Organization newOrganization,
            @Nullable AdministrativeRegion newManagingRegion,
            @NotNull ProjectStatus newStatus,
            @NotNull OffsetDateTime newCreatedAt,
            @NotNull List<Location> newLocations,
            @NotNull List<Boundary> newBoundaries
    ) {
        return new Project(
                null,
                newOrganization,
                newManagingRegion,
                this.title(),
                this.description(),
                this.projectType(),
                newStatus,
                newCreatedAt,
                null,
                null,
                newLocations,
                newBoundaries
        );
    }

    public Project withUpdatedDetails(
            @NotNull Organization newOrganization,
            @Nullable AdministrativeRegion newManagingRegion,
            @NotNull Project source,
            @NotNull ProjectStatus newStatus,
            @NotNull List<Location> newLocations,
            @NotNull List<Boundary> newBoundaries
    ) {
        return new Project(
                this.id(),
                newOrganization,
                newManagingRegion,
                source.title(),
                source.description(),
                source.projectType(),
                newStatus,
                this.createdAt(),
                this.deletedAt(),
                this.deletedBy(),
                newLocations,
                newBoundaries
        );
    }

    public Project withDeleted(@NotNull OffsetDateTime newDeletedAt, @NotNull User newDeletedBy) {
        return new Project(
                this.id(),
                this.organization(),
                this.managingRegion(),
                this.title(),
                this.description(),
                this.projectType(),
                this.status(),
                this.createdAt(),
                newDeletedAt,
                newDeletedBy,
                this.locations(),
                this.boundaries()
        );
    }
}
