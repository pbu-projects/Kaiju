package lol.pbu.kaiju.model;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

@Serdeable
public record RoleUpdateRequest(
        @NotNull(message = "Role is required.")
        UserRole role
) {}
