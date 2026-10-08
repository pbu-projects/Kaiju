package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.Shift;
import lol.pbu.kaiju.repository.ProjectRepository;
import lol.pbu.kaiju.repository.SecurityQueryRepository;
import lol.pbu.kaiju.repository.ShiftRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.Permission;
import lol.pbu.kaiju.util.ControllerUtils;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.BAD_REQUEST;
import static io.micronaut.http.HttpStatus.FORBIDDEN;
import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/shifts")
public class ShiftController implements ControllerUtils {

    private final ShiftRepository shiftRepository;
    private final ProjectRepository projectRepository;
    private final SecurityQueryRepository queryRepository;
    private final UserRepository userRepository;

    public ShiftController(
            ShiftRepository shiftRepository,
            ProjectRepository projectRepository,
            SecurityQueryRepository queryRepository,
            UserRepository userRepository
    ) {
        this.shiftRepository = shiftRepository;
        this.projectRepository = projectRepository;
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
    }

    @Get
    public CursoredPage<Shift> getShifts(@Valid CursoredPageable pageable) {
        return shiftRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Optional<Shift> getShift(@PathVariable UUID id) {
        return shiftRepository.findById(id);
    }

    @Post
    public Shift addShift(@Valid @Body Shift shift, Principal principal) {
        if (shift.project() == null || shift.project().id() == null) {
            throw new HttpStatusException(BAD_REQUEST, "Shift project is required");
        }
        Project project = projectRepository.findById(shift.project().id())
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Project not found"));
        verifyShiftAuthority(principal, project);
        return shiftRepository.save(shift);
    }

    /**
     * Updates an existing shift by its ID after validating that it exists.
     * Enforces that the caller is an ORG_MANAGER or ORG_ADMIN for the shift's project.
     * Throws 403 FORBIDDEN if the user does not manage the project.
     * Throws 404 NOT_FOUND if the shift does not exist.
     *
     * @param id        the ID of the shift to update
     * @param shift     the updated shift details
     * @param principal the authenticated principal
     * @return the updated shift
     */
    @Put("/{id}")
    public Shift updateShift(@PathVariable UUID id, @Valid @Body Shift shift, Principal principal) {
        Shift existing = shiftRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Shift not found"));
        verifyShiftAuthority(principal, existing.project());
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

    /**
     * Deletes a shift by its ID after validating that it exists.
     * Enforces that the caller is an ORG_MANAGER or ORG_ADMIN for the shift's project.
     * Throws 403 FORBIDDEN if the user does not manage the project.
     * Throws 404 NOT_FOUND if the shift does not exist.
     *
     * @param id        the ID of the shift to delete
     * @param principal the authenticated principal
     */
    @Delete("/{id}")
    public void deleteShift(@PathVariable UUID id, Principal principal) {
        Shift existing = shiftRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Shift not found"));
        verifyShiftAuthority(principal, existing.project());
        shiftRepository.deleteById(id);
    }

    private void verifyShiftAuthority(Principal principal, Project project) {
        UUID callerId = UUID.fromString(principal.getName());
        boolean isSysAdmin = userRepository.findById(callerId)
                .map(u -> u.role().hasPermission(Permission.SYSTEM_ADMIN))
                .orElse(false);
        if (isSysAdmin) {
            return;
        }
        if (project.organization() == null || !queryRepository.isOrgManager(callerId, project.organization().id())) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: Only organization managers or system administrators may manage shifts for this project");
        }
    }
}
