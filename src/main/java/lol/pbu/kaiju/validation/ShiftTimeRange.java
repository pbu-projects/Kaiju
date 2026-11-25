package lol.pbu.kaiju.validation;

import java.time.OffsetDateTime;

public interface ShiftTimeRange {

    OffsetDateTime startTime();

    OffsetDateTime endTime();
}
