package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinTable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_MANY;
import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_ONE;

@MappedEntity("shifts")
public record Shift(
        @Id
        @GeneratedValue
        UUID id,
        
        @Relation(MANY_TO_ONE)
        @NotNull(message = "Shift project is required.")
        Project project,

        @Nullable
        boolean isVirtual,
        
        @Relation(MANY_TO_ONE)
        @Nullable Location location,
        
        @NotNull(message = "Shift start time is required.")
        OffsetDateTime startTime,
        
        @NotNull(message = "Shift end time is required.")
        OffsetDateTime endTime,

        @Relation(MANY_TO_MANY)
        @JoinTable(name = "shift_tags")
        List<Tag> tags
) {
    @AssertTrue(message = "A shift must have a location if it is not virtual, and must not have a location if it is virtual.")
    public boolean isValidLocationLogic() {
        return (isVirtual && location == null) || (!isVirtual && location != null);
    }
}
