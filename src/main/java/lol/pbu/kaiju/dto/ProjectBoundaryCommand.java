package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Child boundary command embedded inside Project commands.
 * CRITICAL: Contains NO 'id' field, eliminating child entity mass assignment (Issue #26).
 */
@Serdeable
public record ProjectBoundaryCommand(
        @NotBlank(message = "Boundary name is required.")
        @Size(min = 1, max = 255, message = "Boundary name must be between 1 and 255 characters.")
        String name,

        @NotNull(message = "Boundary coordinates are required.")
        @Size(min = 3, message = "A polygon boundary must contain at least 3 coordinates.")
        @Valid
        List<CoordinateDto> coordinates
) {}
