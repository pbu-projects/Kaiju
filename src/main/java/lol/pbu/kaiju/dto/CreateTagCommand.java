package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Serdeable
public record CreateTagCommand(
        @NotBlank(message = "Tag name is required.")
        @Size(min = 1, max = 50, message = "Tag name must be between 1 and 50 characters.")
        String name
) {}
