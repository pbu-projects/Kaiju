package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinTable;
import lol.pbu.kaiju.core.model.ProjectStatus;

import java.util.List;
import java.util.UUID;

@MappedEntity("projects")
public record Project(
        @Id
        @GeneratedValue
        UUID id,

        String title,
        String description,
        ProjectStatus status,

        // Micronaut automatically handles the 'project_locations' join table behind the scenes
        @Relation(
                value = Relation.Kind.MANY_TO_MANY,
                cascade = Relation.Cascade.PERSIST
        )
        @JoinTable(name = "project_locations")
        List<Location> locations
) {
    // Optional: Add a compact constructor to set defaults if desired
    public Project {
        if (status == null) status = ProjectStatus.DRAFT;
        if (locations == null) locations = List.of();
    }
}