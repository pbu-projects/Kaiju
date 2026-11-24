package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Serdeable
@ValidTimeRange
@ValidShiftLocation
public record CreateShiftRequest(
        @NotNull(message = "Shift project is required.")
        UUID projectId,

        boolean isVirtual,

        @Nullable
        UUID locationId,

        @NotNull(message = "Shift start time is required.")
        OffsetDateTime startTime,

        @NotNull(message = "Shift end time is required.")
        OffsetDateTime endTime,

        @Nullable
        @Size(max = 50, message = "A shift cannot have more than 50 tags.")
        List<UUID> tagIds
) implements ShiftTimeRange, ShiftLocation {
}
