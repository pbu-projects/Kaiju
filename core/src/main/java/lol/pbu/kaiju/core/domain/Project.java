package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinTable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.core.model.ProjectStatus;
import lol.pbu.kaiju.core.model.ProjectType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_MANY;
import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_ONE;

@MappedEntity("projects")
public record Project(
        @Id
        @GeneratedValue
        UUID id,
        
        @Relation(MANY_TO_ONE)
        @NotNull(message = "Project organization is required.")
        Organization organization,
        
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
        @Nullable User deletedBy,

        @Relation(MANY_TO_MANY)
        @JoinTable(name = "project_locations")
        List<Location> locations,

        @Relation(MANY_TO_MANY)
        @JoinTable(name = "project_boundaries")
        List<Boundary> boundaries
) {
}
