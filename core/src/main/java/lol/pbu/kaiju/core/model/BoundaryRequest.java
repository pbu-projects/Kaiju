package lol.pbu.kaiju.core.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Serdeable
public record BoundaryRequest(
        @NotBlank(message = "Boundary name is required")
        @Size(min = 1, max = 255, message = "Boundary name must be between 1 and 255 characters")
        String name,

        @Nullable
        String wkt
) {
}
