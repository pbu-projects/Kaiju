package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Note: 'role' is intentionally excluded to prevent self-privilege escalation during profile update.
 * Roles are modified exclusively via AdminUserController#updateUserRole.
 */
@Serdeable
public record UpdateUserCommand(
        @NotBlank(message = "User email is required.")
        @Email(message = "User email must be a valid email address.")
        @Size(min = 1, max = 255, message = "User email must be between 1 and 255 characters.")
        String email
) {}
