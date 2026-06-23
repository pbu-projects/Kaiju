package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.core.model.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

@MappedEntity("users")
public record User(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "User email is required.")
        @Email(message = "User email must be a valid email address.")
        @Size(min = 1, max = 255, message = "User email must be between 1 and 255 characters.")
        String email,

        @NotNull(message = "User role is required.")
        UserRole role,
        OffsetDateTime createdAt
) {}
