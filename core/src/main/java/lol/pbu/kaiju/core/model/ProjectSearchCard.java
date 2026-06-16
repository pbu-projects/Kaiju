package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;

import java.time.OffsetDateTime;
import java.util.UUID;

@Serdeable
public record ProjectSearchCard(UUID projectId, String projectTitle, UUID locationId, String locationName,
                                OffsetDateTime nextShiftStart) {
}
