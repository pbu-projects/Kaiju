package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
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
import lol.pbu.kaiju.util.PageableUtils;
import lol.pbu.kaiju.util.SpatialMappingService;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/boundaries")
public class BoundaryController {

    public static final String DEFAULT_SORT_FIELD = "name";

    private final BoundaryRepository boundaryRepository;
    private final SpatialMappingService spatialMappingService;

    public BoundaryController(BoundaryRepository boundaryRepository, SpatialMappingService spatialMappingService) {
        this.boundaryRepository = boundaryRepository;
        this.spatialMappingService = spatialMappingService;
    }

    @Get
    public CursoredPage<Boundary> getBoundaries(@Nullable @Valid CursoredPageable pageable) {
        return boundaryRepository.findAll(PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    @Get("/{id}")
    public Optional<Boundary> getBoundary(@PathVariable UUID id) {
        return boundaryRepository.findById(id);
    }

    @Post
    public Boundary addBoundary(@Valid @Body CreateBoundaryCommand command) {
        Boundary boundary = new Boundary(
                null,
                command.name(),
                spatialMappingService.toPolygon(command.coordinates())
        );
        return boundaryRepository.save(boundary);
    }

    /**
     * Updates an existing boundary by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the boundary does not exist.
     *
     * @param id      the ID of the boundary to update
     * @param command the updated boundary details
     * @return the updated boundary
     */
    @Put("/{id}")
    public Boundary updateBoundary(@PathVariable UUID id, @Valid @Body UpdateBoundaryCommand command) {
        boundaryRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Boundary not found"));
        Boundary boundary = new Boundary(
                id,
                command.name(),
                spatialMappingService.toPolygon(command.coordinates())
        );
        return boundaryRepository.update(boundary);
    }

    /**
     * Deletes a boundary by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the boundary does not exist.
     *
     * @param id the ID of the boundary to delete
     */
    @Delete("/{id}")
    public void deleteBoundary(@PathVariable UUID id) {
        long deletedCount = boundaryRepository.removeById(id);
        if (deletedCount == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Boundary not found");
        }
    }
}
