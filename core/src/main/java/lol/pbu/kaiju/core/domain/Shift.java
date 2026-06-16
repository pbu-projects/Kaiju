package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.*;
import io.micronaut.data.annotation.sql.JoinTable;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_ONE;

@MappedEntity("shifts")
public record Shift(
        @Id @GeneratedValue UUID id,
        
        @Relation(MANY_TO_ONE)
        @MappedProperty("project_id")
        @NotNull Project project,
        
        boolean isVirtual,
        
        @Relation(MANY_TO_ONE)
        @Nullable Location location,
        
        @NotNull OffsetDateTime startTime,
        @NotNull OffsetDateTime endTime,

        @Relation(value = Relation.Kind.MANY_TO_MANY)
        @JoinTable(name = "shift_tags")
        List<Tag> tags
) {

    // Compatibility methods
    public UUID locationId() {
        return location != null ? location.id() : null;
    }
    
    public String name() {
        return project != null ? project.title() : "Unnamed Shift";
    }
}
