package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Boundary;
import lol.pbu.kaiju.core.repository.BoundaryRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
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

    @Put("/{id}")
    public Boundary updateBoundary(@PathVariable UUID id, @Valid @Body Boundary boundary) {
        if (!boundaryRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Boundary not found");
        }
        return boundaryRepository.update(boundary.withId(id));
    }

    @Delete("/{id}")
    public void deleteBoundary(@PathVariable UUID id) {
        boundaryRepository.deleteById(id);
    }
}
