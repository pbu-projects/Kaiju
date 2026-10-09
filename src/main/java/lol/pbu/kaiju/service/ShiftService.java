package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.Shift;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface ShiftService {

    @NonNull
    CursoredPage<Shift> getShifts(@NonNull CursoredPageable pageable);

    @NonNull
    Optional<Shift> getShiftById(@NonNull UUID id);

    @NonNull
    Shift createShift(@NonNull Shift shift, @NonNull UUID actorUserId);

    @NonNull
    Shift updateShift(@NonNull UUID id, @NonNull Shift shift, @NonNull UUID actorUserId);

    void deleteShift(@NonNull UUID id, @NonNull UUID actorUserId);
}
