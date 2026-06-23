package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import jakarta.validation.constraints.NotBlank;
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
) {}
