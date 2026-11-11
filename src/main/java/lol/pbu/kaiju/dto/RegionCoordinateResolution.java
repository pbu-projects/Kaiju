package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable
public record RegionCoordinateResolution(
        @Nullable String state,
        @Nullable String county,
        @Nullable String city,
        @Nullable String timeZone
) {
}
