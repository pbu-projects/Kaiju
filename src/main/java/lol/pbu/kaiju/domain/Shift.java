package lol.pbu.kaiju.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinTable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_MANY;
import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_ONE;

@Serdeable
@MappedEntity("shifts")
public record Shift(
        @Id
        @GeneratedValue
        UUID id,
        
        @Relation(MANY_TO_ONE)
        @NotNull(message = "Shift project is required.")
        Project project,

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
    public Shift {
        if ((isVirtual && location != null) || (!isVirtual && location == null)) {
            throw new ValidationException("A shift must have a location if it is not virtual, and must not have a location if it is virtual.");
        }
    }

    public static Shift forSubmission(
            @Nullable UUID id,
            @Nullable Project project,
            boolean isVirtual,
            @Nullable Location location,
            @NotNull OffsetDateTime startTime,
            @NotNull OffsetDateTime endTime,
            @Nullable List<Tag> tags
    ) {
        return new Shift(id, project, isVirtual, location, startTime, endTime, tags);
    }

    /**
     * Instead of using setters, this method gives the opportunity to take an existing shift ID and assign that to
     * the shift properties in this shift record.
     *
     * @param newId The UUID to assign to the shift.
     * @return A new {@link Shift} with the given ID.
     */
    public Shift withId(@NotNull UUID newId) {
        return new Shift(
                newId,
                this.project(),
                this.isVirtual(),
                this.location(),
                this.startTime(),
                this.endTime(),
                this.tags()
        );
    }

    public Shift withCreationDetails(
            @NotNull Project newProject,
            @Nullable Location newLocation,
            @NotNull List<Tag> newTags
    ) {
        return new Shift(
                null,
                newProject,
                this.isVirtual(),
                newLocation,
                this.startTime(),
                this.endTime(),
                newTags
        );
    }

    public Shift withUpdatedDetails(
            @NotNull Shift source,
            @Nullable Location newLocation,
            @NotNull List<Tag> newTags
    ) {
        return new Shift(
                this.id(),
                this.project(),
                source.isVirtual(),
                newLocation,
                source.startTime(),
                source.endTime(),
                newTags
        );
    }
}
