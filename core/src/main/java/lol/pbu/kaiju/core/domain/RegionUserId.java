package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.data.annotation.Embeddable;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.serde.annotation.Serdeable;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Introspected
@Serdeable
public record RegionUserId(
        @MappedProperty("user_id") UUID userId,
        @MappedProperty("region_id") UUID regionId
) implements Serializable {}
