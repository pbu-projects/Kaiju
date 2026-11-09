package lol.pbu.kaiju.sluice;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SluiceReverseGeocodeResult(
        @Nullable String state,
        @Nullable String county,
        @Nullable String city,
        @Nullable String timeZone
) {
}
