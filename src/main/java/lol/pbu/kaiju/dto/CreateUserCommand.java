package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.UserRole;

/**
 * Input for creating a new user account.
 * The {@code role} field is optional and defaults to {@code STANDARD_USER}.
 * Only callers with user-management permission can assign a higher role.
 */
@Serdeable
public record CreateUserCommand(
        @NotBlank(message = "User email is required.")
        @Email(message = "User email must be a valid email address.")
        @Size(min = 1, max = 255, message = "User email must be between 1 and 255 characters.")
        String email,

        @Nullable
        UserRole role
) {}
