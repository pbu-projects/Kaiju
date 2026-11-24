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
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.Shift;
import lol.pbu.kaiju.domain.Tag;
import lol.pbu.kaiju.dto.CreateShiftRequest;
import lol.pbu.kaiju.dto.UpdateShiftRequest;
import lol.pbu.kaiju.security.AuthorizationService;
import lol.pbu.kaiju.service.ShiftService;
import lol.pbu.kaiju.util.PageRequests;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/shifts")
public class ShiftController {

    public static final String DEFAULT_SORT_FIELD = "id";

    private final ShiftService shiftService;
    private final AuthorizationService authorizationService;

    public ShiftController(
            ShiftService shiftService,
            AuthorizationService authorizationService
    ) {
        this.shiftService = shiftService;
        this.authorizationService = authorizationService;
    }

    @Get
    public CursoredPage<Shift> getShifts(@Nullable @Valid CursoredPageable pageable, @Nullable Principal principal) {
        CursoredPageable resolved = PageRequests.resolvePageable(pageable, DEFAULT_SORT_FIELD);
        UUID userId = principal != null ? UUID.fromString(principal.getName()) : null;
        if (authorizationService.isSystemAdmin(userId)) {
            return shiftService.getShifts(resolved);
        }
        return shiftService.getActiveShifts(resolved);
    }

    @Get("/{id}")
    public Optional<Shift> getShift(@PathVariable UUID id, @Nullable Principal principal) {
        UUID userId = principal != null ? UUID.fromString(principal.getName()) : null;
        return shiftService.getShiftById(id)
                .map(shift -> {
                    authorizationService.authorizeShiftView(userId, shift);
                    return shift;
                });
    }

    @Post
    public Shift addShift(@Valid @Body CreateShiftRequest request, Principal principal) {
        Shift shift = new Shift(
                null,
                stubProject(request.projectId()),
                request.isVirtual(),
                stubLocation(request.locationId()),
                request.startTime(),
                request.endTime(),
                stubTags(request.tagIds())
        );
        UUID actorUserId = UUID.fromString(principal.getName());
        return shiftService.createShift(shift, actorUserId);
    }

    /**
     * Updates an existing shift by its ID after validating that it exists.
     * Allows system admins, organization managers or admins, regional admins with jurisdiction, and project sponsors.
     * Throws 403 FORBIDDEN if the caller is not allowed to manage shifts for the project.
     * Throws 404 NOT_FOUND if the shift does not exist.
     *
     * @param id        the ID of the shift to update
     * @param request   the updated shift details
     * @param principal the authenticated principal
     * @return the updated shift
     */
    @Put("/{id}")
    public Shift updateShift(@PathVariable UUID id, @Valid @Body UpdateShiftRequest request, Principal principal) {
        Shift candidate = new Shift(
                id,
                null,
                request.isVirtual(),
                stubLocation(request.locationId()),
                request.startTime(),
                request.endTime(),
                stubTags(request.tagIds())
        );
        UUID actorUserId = UUID.fromString(principal.getName());
        return shiftService.updateShift(id, candidate, actorUserId);
    }

    /**
     * Deletes a shift by its ID after validating that it exists.
     * Allows system admins, organization managers or admins, regional admins with jurisdiction, and project sponsors.
     * Throws 403 FORBIDDEN if the caller is not allowed to manage shifts for the project.
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

    private Project stubProject(UUID projectId) {
        return new Project(projectId, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Location stubLocation(@Nullable UUID locationId) {
        if (locationId == null) {
            return null;
        }
        return new Location(locationId, null, null, null, null, null, null, null);
    }

    private List<Tag> stubTags(@Nullable List<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        return tagIds.stream()
                .map(id -> new Tag(id, null))
                .toList();
    }
}
