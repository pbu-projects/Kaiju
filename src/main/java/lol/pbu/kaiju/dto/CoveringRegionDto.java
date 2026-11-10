package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Introspected
@Serdeable
public record CoveringRegionDto(
        UUID id,
        String name,
        @Nullable Integer level,
        @Nullable String countryCode,
        @Nullable String stateCode,
        @Nullable String timeZone
) {
}
