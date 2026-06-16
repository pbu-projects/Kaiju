package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;

import java.time.OffsetDateTime;
import java.util.UUID;

@Serdeable
public record ShiftResponse(UUID id, UUID locationId, String name, OffsetDateTime startTime, OffsetDateTime endTime) {
}