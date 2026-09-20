package lol.pbu.kaiju.controller;
import lol.pbu.kaiju.util.ExistenceValidator;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Boundary;
import lol.pbu.kaiju.repository.BoundaryRepository;


import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/boundaries")
public class BoundaryController implements ExistenceValidator {

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
     *
     * @param id       the ID of the boundary to update
     * @param boundary the updated boundary details
     * @return the updated boundary
     */
    @Put("/{id}")
    public Boundary updateBoundary(@PathVariable UUID id, @Valid @Body Boundary boundary) {
        checkExists(boundaryRepository, id);
        
        // We explicitly instantiate a new Boundary rather than using a wither method.
        // This manual mapping prevents mass-assignment vulnerabilities. If a protected
        // field (like createdAt) is added later, a wither method would blindly copy
        // the user's unvalidated payload.
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
     *
     * @param id the ID of the boundary to delete
     */
    @Delete("/{id}")
    public void deleteBoundary(@PathVariable UUID id) {
        checkExists(boundaryRepository, id);
        boundaryRepository.deleteById(id);
    }

}
