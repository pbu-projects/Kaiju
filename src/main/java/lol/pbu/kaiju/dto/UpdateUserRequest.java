package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Input for updating a user's profile.
 * Excludes {@code role} so users cannot raise their own access level during profile edits.
 * Roles can only be changed by a system admin through the user role endpoint.
 */
@Serdeable
public record UpdateUserRequest(
        @NotBlank(message = "User email is required.")
        @Email(message = "User email must be a valid email address.")
        @Size(min = 1, max = 255, message = "User email must be between 1 and 255 characters.")
        String email
) {}
