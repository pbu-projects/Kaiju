package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.core.model.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

@MappedEntity("users")
public record User(
        @Id @GeneratedValue UUID id,
        @NotBlank @Email String email,
        @NotNull UserRole role,
        OffsetDateTime createdAt
) {}
