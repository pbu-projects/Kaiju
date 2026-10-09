package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.model.ProjectType;

import java.util.List;
import java.util.UUID;

@Serdeable
public record CreateProjectCommand(
        @NotNull(message = "Project organization ID is required.")
        UUID organizationId,

        @Nullable
        UUID managingRegionId,

        @NotBlank(message = "Project title is required.")
        @Size(min = 1, max = 255, message = "Project title must be between 1 and 255 characters.")
        String title,

        @NotBlank(message = "Project description is required.")
        @Size(min = 20, max = 4000, message = "Project description must be between 20 and 4000 characters.")
        String description,

        @NotNull(message = "Project type is required.")
        ProjectType projectType,

        @Nullable
        ProjectStatus status,

        @Nullable
        @Valid
        List<ProjectLocationCommand> locations,

        @NotNull(message = "Project boundaries are required.")
        @NotEmpty(message = "Project boundaries must not be empty.")
        @Valid
        List<ProjectBoundaryCommand> boundaries
) {}
