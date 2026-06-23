package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@MappedEntity("tags")
public record Tag(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "Tag name is required.")
        @Size(min = 1, max = 50, message = "Tag name must be between 1 and 50 characters.")
        String name
) {
    /**
     * Instead of using setters, this method gives the opportunity to take an existing tag ID and assign that to
     * the tag properties in this tag record.
     *
     * @param newId The UUID to assign to the tag.
     * @return A new {@link Tag} with the given ID.
     */
    public Tag withId(@NotNull UUID newId) {
        return new Tag(
                newId,
                this.name()
        );
    }
}
