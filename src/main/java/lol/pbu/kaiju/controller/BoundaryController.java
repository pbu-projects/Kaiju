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
import lol.pbu.kaiju.domain.Boundary;
import lol.pbu.kaiju.dto.CreateBoundaryCommand;
import lol.pbu.kaiju.dto.UpdateBoundaryCommand;
import lol.pbu.kaiju.repository.BoundaryRepository;
import lol.pbu.kaiju.security.ProjectSecurityService;
import lol.pbu.kaiju.util.PageableUtils;
import lol.pbu.kaiju.util.SpatialMappingService;
import org.locationtech.jts.geom.Geometry;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.security.Permission.REGION_MANAGE_CLAIM;
import static lol.pbu.kaiju.security.Permission.SYSTEM_ADMIN_CLAIM;

/**
 * REST controller for managing geographic boundaries.
 */
@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/boundaries")
public class BoundaryController {

    public static final String DEFAULT_SORT_FIELD = "name";
    private static final String BOUNDARY_NOT_FOUND = "Boundary not found";

    private final BoundaryRepository boundaryRepository;
    private final SpatialMappingService spatialMappingService;
    private final ProjectSecurityService projectSecurityService;

    public BoundaryController(
            BoundaryRepository boundaryRepository,
            SpatialMappingService spatialMappingService,
            ProjectSecurityService projectSecurityService
    ) {
        this.boundaryRepository = boundaryRepository;
        this.spatialMappingService = spatialMappingService;
        this.projectSecurityService = projectSecurityService;
    }

    /**
     * Retrieves a paginated list of geographic boundaries.
     *
     * @param pageable pagination and sorting parameters
     * @return a cursored page of {@link Boundary} records
     */
    @Get
    public CursoredPage<Boundary> getBoundaries(@Nullable @Valid CursoredPageable pageable) {
        return boundaryRepository.findAll(PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    /**
     * Retrieves a specific boundary by its identifier.
     *
     * @param id the unique identifier of the boundary
     * @return an optional containing the {@link Boundary} if found
     */
    @Get("/{id}")
    public Optional<Boundary> getBoundary(@PathVariable UUID id) {
        return boundaryRepository.findById(id);
    }

    /**
     * Creates a new boundary polygon.
     * Restricted to platform administrators and directors of an intersecting region.
     *
     * @param command   the payload containing boundary name and polygon coordinates
     * @param principal the authenticated caller
     * @return the persisted {@link Boundary}
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Post
    public Boundary addBoundary(@Valid @Body CreateBoundaryCommand command, Principal principal) {
        Geometry polygon = spatialMappingService.toPolygon(command.coordinates());
        projectSecurityService.authorizeBoundaryCreate(UUID.fromString(principal.getName()), polygon);
        Boundary boundary = new Boundary(
                null,
                command.name(),
                polygon
        );
        return boundaryRepository.save(boundary);
    }

    /**
     * Updates an existing boundary by its ID after validating that it exists and is within caller jurisdiction.
     *
     * @param id        the ID of the boundary to update
     * @param command   the updated boundary details
     * @param principal the authenticated caller
     * @return the updated boundary
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Put("/{id}")
    public Boundary updateBoundary(
            @PathVariable UUID id,
            @Valid @Body UpdateBoundaryCommand command,
            Principal principal
    ) {
        Geometry polygon = spatialMappingService.toPolygon(command.coordinates());
        projectSecurityService.authorizeBoundaryUpdate(UUID.fromString(principal.getName()), id, polygon);
        if (!boundaryRepository.existsById(id)) {
            throw new HttpStatusException(NOT_FOUND, BOUNDARY_NOT_FOUND);
        }
        Boundary boundary = new Boundary(
                id,
                command.name(),
                polygon
        );
        return boundaryRepository.update(boundary);
    }

    /**
     * Deletes a boundary by its ID after validating that it exists and is within caller jurisdiction.
     *
     * @param id        the ID of the boundary to delete
     * @param principal the authenticated caller
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Delete("/{id}")
    public void deleteBoundary(@PathVariable UUID id, Principal principal) {
        UUID actorId = UUID.fromString(principal.getName());
        projectSecurityService.authorizeBoundaryDelete(actorId, id);
        long deletedCount = boundaryRepository.removeById(id);
        if (deletedCount == 0) {
            throw new HttpStatusException(NOT_FOUND, BOUNDARY_NOT_FOUND);
        }
    }
}
