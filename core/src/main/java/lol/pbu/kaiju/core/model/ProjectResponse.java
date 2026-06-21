package lol.pbu.kaiju.core.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.OffsetDateTime;
import java.util.UUID;

@Serdeable
public record ProjectResponse(
        UUID id,
        UUID organizationId,
        String title,
        String description,
        ProjectType projectType,
        ProjectStatus status,
        OffsetDateTime createdAt,
        @Nullable OffsetDateTime deletedAt,
        @Nullable UUID deletedById
) {
}
