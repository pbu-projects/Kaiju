package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

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
        List<UUID> tagIds
) {
    @AssertTrue(message = "A shift must have a location if it is not virtual, and must not have a location if it is virtual.")
    public boolean isValidLocationLogic() {
        return (isVirtual && locationId == null) || (!isVirtual && locationId != null);
    }
}
