package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Serdeable
public record CreateBoundaryRequest(
        @NotBlank(message = "Boundary name is required.")
        @Size(min = 1, max = 255, message = "Boundary name must be between 1 and 255 characters.")
        String name,

        @NotNull(message = "Boundary coordinates are required.")
        @Size(min = 3, max = 5000, message = "A polygon boundary must contain between 3 and 5000 coordinates.")
        @Valid
        List<Coordinates> coordinates
) {}
