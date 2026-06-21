package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Serdeable
public record UserRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        @Size(max = 255)
        String email,

        @NotNull(message = "Role is required")
        UserRole role
) {
}
