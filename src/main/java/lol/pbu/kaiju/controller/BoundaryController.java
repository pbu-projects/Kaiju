package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Boundary;
import lol.pbu.kaiju.repository.BoundaryRepository;

import java.util.Optional;
import java.util.UUID;
import static io.micronaut.http.HttpStatus.NOT_FOUND;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("isAuthenticated()")
@Controller("/boundaries")
public class BoundaryController {

    private final BoundaryRepository boundaryRepository;

    public BoundaryController(BoundaryRepository boundaryRepository) {
        this.boundaryRepository = boundaryRepository;
    }

    @Get
    public CursoredPage<Boundary> getBoundaries(@Valid CursoredPageable pageable) {
        return boundaryRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Optional<Boundary> getBoundary(@PathVariable UUID id) {
        return boundaryRepository.findById(id);
    }

    @Post
    public Boundary addBoundary(@Valid @Body Boundary boundary) {
        return boundaryRepository.save(boundary);
    }

    /**
     * Updates an existing boundary by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the boundary does not exist.
     * Refer to the sister method {@link #updateBoundaryNoLook(UUID, Boundary)} to update without validation.
     *
     * @param id       the ID of the boundary to update
     * @param boundary the updated boundary details
     * @return the updated boundary
     */
    @Put("/{id}")
    public Boundary updateBoundary(@PathVariable UUID id, @Valid @Body Boundary boundary) {
        Boundary existing = boundaryRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Boundary not found"));
        
        Boundary securePayload = new Boundary(
                id,
                boundary.name(),
                boundary.geom()
        );
        return boundaryRepository.update(securePayload);
    }


    /**
     * Deletes a boundary by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the boundary does not exist.
     * Refer to the sister method {@link #deleteBoundaryNoLook(UUID)} to delete without validation.
     *
     * @param id the ID of the boundary to delete
     */
    @Delete("/{id}")
    public void deleteBoundary(@PathVariable UUID id) {
        if (!boundaryRepository.existsById(id)) {
            throw new HttpStatusException(NOT_FOUND, "Boundary not found");
        }
        boundaryRepository.deleteById(id);
    }

}
