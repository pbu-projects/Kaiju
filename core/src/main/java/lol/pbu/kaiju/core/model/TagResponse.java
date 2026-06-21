package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable
public record TagResponse(
        UUID id,
        String name
) {
}
