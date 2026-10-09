package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.Shift;
import lol.pbu.kaiju.domain.Tag;
import lol.pbu.kaiju.dto.CreateShiftCommand;
import lol.pbu.kaiju.dto.UpdateShiftCommand;
import lol.pbu.kaiju.repository.LocationRepository;
import lol.pbu.kaiju.repository.TagRepository;
import lol.pbu.kaiju.service.ShiftService;
import lol.pbu.kaiju.util.PageableUtils;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.BAD_REQUEST;
import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/shifts")
public class ShiftController {

    public static final String DEFAULT_SORT_FIELD = "id";

    private final ShiftService shiftService;
    private final LocationRepository locationRepository;
    private final TagRepository tagRepository;

    public ShiftController(
            ShiftService shiftService,
            LocationRepository locationRepository,
            TagRepository tagRepository
    ) {
        this.shiftService = shiftService;
        this.locationRepository = locationRepository;
        this.tagRepository = tagRepository;
    }

    @Get
    public CursoredPage<Shift> getShifts(@Nullable @Valid CursoredPageable pageable) {
        return shiftService.getShifts(PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    @Get("/{id}")
    public Optional<Shift> getShift(@PathVariable UUID id) {
        return shiftService.getShiftById(id);
    }

    @Post
    public Shift addShift(@Valid @Body CreateShiftCommand command, Principal principal) {
        if (!command.isValidLocationLogic()) {
            throw new HttpStatusException(BAD_REQUEST, "A shift must have a location if it is not virtual, and must not have a location if it is virtual.");
        }
        Location location = resolveLocation(command.locationId());
        List<Tag> tags = resolveTags(command.tagIds());
        Project stubProject = new Project(
                command.projectId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Shift shift = new Shift(
                null,
                stubProject,
                command.isVirtual(),
                location,
                command.startTime(),
                command.endTime(),
                tags
        );
        UUID actorUserId = UUID.fromString(principal.getName());
        return shiftService.createShift(shift, actorUserId);
    }

    /**
     * Updates an existing shift by its ID after validating that it exists.
     * Enforces that the caller is an ORG_MANAGER or ORG_ADMIN for the shift's project.
     * Throws 403 FORBIDDEN if the user does not manage the project.
     * Throws 404 NOT_FOUND if the shift does not exist.
     *
     * @param id        the ID of the shift to update
     * @param command   the updated shift details
     * @param principal the authenticated principal
     * @return the updated shift
     */
    @Put("/{id}")
    public Shift updateShift(@PathVariable UUID id, @Valid @Body UpdateShiftCommand command, Principal principal) {
        if (!command.isValidLocationLogic()) {
            throw new HttpStatusException(BAD_REQUEST, "A shift must have a location if it is not virtual, and must not have a location if it is virtual.");
        }
        Location location = resolveLocation(command.locationId());
        List<Tag> tags = resolveTags(command.tagIds());
        Shift candidate = new Shift(
                id,
                null,
                command.isVirtual(),
                location,
                command.startTime(),
                command.endTime(),
                tags
        );
        UUID actorUserId = UUID.fromString(principal.getName());
        return shiftService.updateShift(id, candidate, actorUserId);
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
        UUID actorUserId = UUID.fromString(principal.getName());
        shiftService.deleteShift(id, actorUserId);
    }

    private Location resolveLocation(UUID locationId) {
        if (locationId == null) {
            return null;
        }
        return locationRepository.findById(locationId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Location not found"));
    }

    private List<Tag> resolveTags(List<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        return tagIds.stream()
                .map(tagRepository::findById)
                .flatMap(Optional::stream)
                .toList();
    }
}
