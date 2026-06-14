package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinTable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.core.model.ProjectStatus;
import lol.pbu.kaiju.core.model.ProjectType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@MappedEntity("projects")
public record Project(
        @Id @GeneratedValue UUID id,
        
        @Relation(Relation.Kind.MANY_TO_ONE)
        @NotNull Organization organization,
        
        @NotBlank String title,
        @NotBlank String description,
        @NotNull ProjectType projectType,
        @NotNull ProjectStatus status,
        OffsetDateTime createdAt,
        @Nullable OffsetDateTime deletedAt,
        
        @Relation(Relation.Kind.MANY_TO_ONE)
        @Nullable User deletedBy,

        @Relation(value = Relation.Kind.MANY_TO_MANY)
        @JoinTable(name = "project_locations")
        List<Location> locations,

        @Relation(value = Relation.Kind.MANY_TO_MANY)
        @JoinTable(name = "project_boundaries")
        List<Boundary> boundaries
) {
    // Compatibility constructor (partial)
    public Project(UUID id, String title, String description, ProjectStatus status, List<Location> locations) {
        this(id, null, title, description, ProjectType.STANDARD, status, OffsetDateTime.now(), null, null, locations, List.of());
    }
}
