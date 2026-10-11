package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

@Serdeable
public record IngestBoundaryCommand(
        @NotBlank(message = "Source URL is required.")
        String sourceUrl,

        @Nullable
        Double tolerance
) {
}
