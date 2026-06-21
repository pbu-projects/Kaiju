package lol.pbu.kaiju.core.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Serdeable
public record ProjectRequest(
        @NotNull(message = "Organization ID is required")
        UUID organizationId,

        @NotBlank(message = "Project title is required")
        @Size(min = 1, max = 255, message = "Title must be between 1 and 255 characters")
        String title,

        @NotBlank(message = "Project description is required")
        String description,

        @NotNull(message = "Project type is required")
        ProjectType projectType,

        @NotNull(message = "Project status is required")
        ProjectStatus status
) {
}
