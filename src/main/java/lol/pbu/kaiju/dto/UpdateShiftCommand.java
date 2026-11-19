package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Serdeable
public record UpdateShiftCommand(
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
) {
    @AssertTrue(message = "A shift must have a location if it is not virtual, and must not have a location if it is virtual.")
    public boolean isValidLocationLogic() {
        return (isVirtual && locationId == null) || (!isVirtual && locationId != null);
    }

    @AssertTrue(message = "Shift start time must be before end time.")
    public boolean isValidTimeRange() {
        return startTime == null || endTime == null || startTime.isBefore(endTime);
    }
}
