package lol.pbu.kaiju.dto;

import java.time.OffsetDateTime;

public interface ShiftTimeRange {

    OffsetDateTime startTime();

    OffsetDateTime endTime();
}
