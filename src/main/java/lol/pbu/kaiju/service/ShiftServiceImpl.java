package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.Shift;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.repository.ProjectRepository;
import lol.pbu.kaiju.repository.SecurityQueryRepository;
import lol.pbu.kaiju.repository.ShiftRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.Permission;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

@Singleton
public class ShiftServiceImpl implements ShiftService {

    private static final String SHIFT_NOT_FOUND = "Shift not found";

    private final ShiftRepository shiftRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final SecurityQueryRepository queryRepository;

    public ShiftServiceImpl(
            ShiftRepository shiftRepository,
            ProjectRepository projectRepository,
            UserRepository userRepository,
            SecurityQueryRepository queryRepository
    ) {
        this.shiftRepository = shiftRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.queryRepository = queryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public CursoredPage<Shift> getShifts(@NonNull CursoredPageable pageable) {
        return shiftRepository.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public Optional<Shift> getShiftById(@NonNull UUID id) {
        return shiftRepository.findById(id);
    }

    @Override
    @Transactional
    @NonNull
    public Shift createShift(@NonNull Shift shift, @NonNull UUID actorUserId) {
        validateShiftInvariants(shift);

        if (shift.project() == null || shift.project().id() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Project is required");
        }

        Project project = projectRepository.findById(shift.project().id())
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        verifyShiftAuthority(actorUserId, project);

        Shift toSave = new Shift(
                null,
                project,
                shift.isVirtual(),
                shift.location(),
                shift.startTime(),
                shift.endTime(),
                shift.tags()
        );
        return shiftRepository.save(toSave);
    }

    @Override
    @Transactional
    @NonNull
    public Shift updateShift(@NonNull UUID id, @NonNull Shift shift, @NonNull UUID actorUserId) {
        validateShiftInvariants(shift);

        Shift existing = shiftRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, SHIFT_NOT_FOUND));

        verifyShiftAuthority(actorUserId, existing.project());

        Shift safeUpdate = new Shift(
                id,
                existing.project(),
                shift.isVirtual(),
                shift.location(),
                shift.startTime(),
                shift.endTime(),
                shift.tags()
        );
        return shiftRepository.update(safeUpdate);
    }

    @Override
    @Transactional
    public void deleteShift(@NonNull UUID id, @NonNull UUID actorUserId) {
        Shift existing = shiftRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, SHIFT_NOT_FOUND));

        verifyShiftAuthority(actorUserId, existing.project());

        long count = shiftRepository.removeById(id);
        if (count == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, SHIFT_NOT_FOUND);
        }
    }

    private void validateShiftInvariants(Shift shift) {
        if (shift.isVirtual() && shift.location() != null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "A shift must have a location if it is not virtual, and must not have a location if it is virtual.");
        }
        if (!shift.isVirtual() && shift.location() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "A shift must have a location if it is not virtual, and must not have a location if it is virtual.");
        }
        if (shift.startTime() != null && shift.endTime() != null && !shift.startTime().isBefore(shift.endTime())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Shift start time must be before end time");
        }
    }

    private void verifyShiftAuthority(UUID actorUserId, Project project) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));
        if (actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return;
        }
        if (project.organization() == null || !queryRepository.isOrgManager(actorUserId, project.organization().id())) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Forbidden: Only organization managers or system administrators may manage shifts for this project");
        }
    }
}
