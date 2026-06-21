package lol.pbu.kaiju.core.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable
public record BoundaryResponse(
        UUID id,
        String name,
        @Nullable String wkt
) {
}
