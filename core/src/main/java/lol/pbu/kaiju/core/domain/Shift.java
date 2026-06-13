package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

@MappedEntity("shifts")
public record Shift(
        @Id @GeneratedValue UUID id,
        UUID locationId,
        String name,
        OffsetDateTime startTime,
        OffsetDateTime endTime) {
}