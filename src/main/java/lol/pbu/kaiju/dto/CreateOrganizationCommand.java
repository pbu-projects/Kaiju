package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Serdeable
public record CreateOrganizationCommand(
        @NotBlank(message = "Organization name is required.")
        @Size(min = 1, max = 255, message = "Organization name must be between 1 and 255 characters.")
        String name,

        @Nullable
        @Size(min = 1, max = 255, message = "Organization website URL must be between 1 and 255 characters.")
        String websiteUrl,

        @Nullable
        UUID parentId,

        @NotNull(message = "isPublic is required.")
        Boolean isPublic
) {}
