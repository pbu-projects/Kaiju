package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.Shift;
import lol.pbu.kaiju.domain.Tag;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.repository.LocationRepository;
import lol.pbu.kaiju.repository.ProjectRepository;
import lol.pbu.kaiju.repository.ShiftRepository;
import lol.pbu.kaiju.repository.TagRepository;
import lol.pbu.kaiju.security.AuthorizationService;
import lol.pbu.kaiju.security.Permission;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class DefaultShiftService implements ShiftService {

    private static final String SHIFT_NOT_FOUND = "Shift not found";

    private final ShiftRepository shiftRepository;
    private final ProjectRepository projectRepository;
    private final LocationRepository locationRepository;
    private final TagRepository tagRepository;
    private final AuthorizationService authorizationService;

    public DefaultShiftService(
            ShiftRepository shiftRepository,
            ProjectRepository projectRepository,
            LocationRepository locationRepository,
            TagRepository tagRepository,
            AuthorizationService authorizationService
    ) {
        this.shiftRepository = shiftRepository;
        this.projectRepository = projectRepository;
        this.locationRepository = locationRepository;
        this.tagRepository = tagRepository;
        this.authorizationService = authorizationService;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public CursoredPage<Shift> getShifts(@NonNull CursoredPageable pageable) {
        return shiftRepository.findByProjectDeletedAtIsNull(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public CursoredPage<Shift> getActiveShifts(@NonNull CursoredPageable pageable) {
        return shiftRepository.findByProjectStatusAndProjectDeletedAtIsNull(ProjectStatus.ACTIVE, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public Optional<Shift> getShiftById(@NonNull UUID id) {
        return shiftRepository.findByIdAndProjectDeletedAtIsNull(id);
    }

    @Override
    @Transactional
    @NonNull
    public Shift createShift(@NonNull Shift shift, @NonNull UUID actorUserId) {
        User actor = authorizationService.requireActor(actorUserId);

        if (shift.project() == null || shift.project().id() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Project is required");
        }

        Project foundProject = projectRepository.findById(shift.project().id()).orElse(null);
        Project project = requireAuthorizedProject(foundProject, actor, "Project not found");

        validateShiftInvariants(shift);
        validateProjectForShift(project, shift);

        Location location = resolveLocation(shift);
        List<Tag> tags = resolveTags(shift.tags());

        Shift toSave = new Shift(
                null,
                project,
                shift.isVirtual(),
                location,
                shift.startTime(),
                shift.endTime(),
                tags
        );
        return shiftRepository.save(toSave);
    }

    @Override
    @Transactional
    @NonNull
    public Shift updateShift(@NonNull UUID id, @NonNull Shift shift, @NonNull UUID actorUserId) {
        User actor = authorizationService.requireActor(actorUserId);

        Shift existing = shiftRepository.findById(id).orElse(null);
        Project project = requireAuthorizedProject(existing != null ? existing.project() : null, actor, SHIFT_NOT_FOUND);

        validateShiftInvariants(shift);
        validateProjectForShift(project, shift);

        Location location = resolveLocation(shift);
        List<Tag> tags = resolveTags(shift.tags());

        Shift safeUpdate = new Shift(
                id,
                project,
                shift.isVirtual(),
                location,
                shift.startTime(),
                shift.endTime(),
                tags
        );
        return shiftRepository.update(safeUpdate);
    }

    @Override
    @Transactional
    public void deleteShift(@NonNull UUID id, @NonNull UUID actorUserId) {
        User actor = authorizationService.requireActor(actorUserId);

        Shift existing = shiftRepository.findById(id).orElse(null);
        requireAuthorizedProject(existing != null ? existing.project() : null, actor, SHIFT_NOT_FOUND);

        long count = shiftRepository.removeById(id);
        if (count == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, SHIFT_NOT_FOUND);
        }
    }

    private Project requireAuthorizedProject(
            @Nullable Project project,
            User actor,
            String missingMessage
    ) {
        if (project == null) {
            if (!actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
                throw new HttpStatusException(HttpStatus.FORBIDDEN, "Forbidden: You do not have permission to manage shifts for this project");
            }
            throw new HttpStatusException(HttpStatus.NOT_FOUND, missingMessage);
        }
        authorizationService.authorizeShiftManagement(actor, project);
        if (project.deletedAt() != null) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, missingMessage);
        }
        return project;
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

    private void validateProjectForShift(Project project, Shift shift) {
        if (project.status() != ProjectStatus.ACTIVE) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Shifts can only be managed for ACTIVE projects");
        }
        if (shift.location() != null && !isLocationAllowedForProject(project, shift.location().id())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Shift location must belong to the project's locations");
        }
    }

    private boolean isLocationAllowedForProject(Project project, UUID locationId) {
        if (locationId == null) {
            return false;
        }
        if (project.locations() != null && !project.locations().isEmpty()) {
            return project.locations().stream()
                    .anyMatch(loc -> Objects.equals(loc.id(), locationId));
        }
        return !projectRepository.hasAnyProjectLocation(project.id())
                || projectRepository.hasProjectLocation(project.id(), locationId);
    }

    @Nullable
    private Location resolveLocation(Shift shift) {
        if (shift.isVirtual() || shift.location() == null) {
            return null;
        }
        UUID locationId = shift.location().id();
        if (locationId == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Location is required");
        }
        return locationRepository.findById(locationId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Location not found"));
    }

    private List<Tag> resolveTags(@Nullable List<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<UUID> tagIds = tags.stream()
                .map(Tag::id)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return tagRepository.findByIdIn(tagIds);
    }
}
