package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinTable;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@MappedEntity("shifts")
public record Shift(
        @Id @GeneratedValue UUID id,
        
        @Relation(Relation.Kind.MANY_TO_ONE)
        @NotNull Project project,
        
        boolean isVirtual,
        
        @Relation(Relation.Kind.MANY_TO_ONE)
        @Nullable Location location,
        
        @NotNull OffsetDateTime startTime,
        @NotNull OffsetDateTime endTime,

        @Relation(value = Relation.Kind.MANY_TO_MANY)
        @JoinTable(name = "shift_tags")
        List<Tag> tags
) {
    // Compatibility constructor
    public Shift(UUID id, UUID locationId, String name, OffsetDateTime startTime, OffsetDateTime endTime) {
        this(id, null, false, null, startTime, endTime, List.of());
    }

    // Compatibility methods
    public UUID locationId() {
        return location != null ? location.id() : null;
    }
    
    public String name() {
        return project != null ? project.title() : "Unnamed Shift";
    }
}
