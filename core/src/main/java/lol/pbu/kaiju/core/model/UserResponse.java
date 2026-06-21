package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;

import java.time.OffsetDateTime;
import java.util.UUID;

@Serdeable
public record UserResponse(
        UUID id,
        String email,
        UserRole role,
        OffsetDateTime createdAt
) {
}
