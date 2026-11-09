package lol.pbu.kaiju.sluice;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Serdeable
public record SluiceBoundaryDto(
        String id,
        String name,
        SluiceBoundaryLevel level,
        @Nullable String stateCode,
        @Nullable String countyName,
        @Nullable String geometryGeoJson,
        @Nullable Map<String, Object> properties
) {
}
