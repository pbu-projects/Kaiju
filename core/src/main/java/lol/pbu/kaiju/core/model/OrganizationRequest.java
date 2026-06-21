package lol.pbu.kaiju.core.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Serdeable
public record OrganizationRequest(
        @NotBlank(message = "An organization name is required")
        @Size(min = 1, max = 255, message = "Organization name must be between 1 and 255 characters")
        String name,

        @Nullable
        @Size(max = 255, message = "Website URL must be at most 255 characters")
        String websiteUrl,

        @Nullable
        UUID parentId
) {
}
