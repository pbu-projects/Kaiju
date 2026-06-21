package lol.pbu.kaiju.core.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable
public record OrganizationResponse(
        UUID id,
        String name,
        @Nullable String websiteUrl,
        @Nullable UUID parentId
) {
}
