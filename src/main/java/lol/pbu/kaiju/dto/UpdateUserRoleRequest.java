package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.UserRole;

@Serdeable
public record UpdateUserRoleRequest(
        @NotNull(message = "Role is required.")
        UserRole role
) {}
