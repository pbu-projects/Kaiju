package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Shift;
import lol.pbu.kaiju.core.repository.ShiftRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/shifts")
public class ShiftController {

    private final ShiftRepository shiftRepository;

    public ShiftController(ShiftRepository shiftRepository) {
        this.shiftRepository = shiftRepository;
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
    public Shift addShift(@Valid @Body Shift shift) {
        return shiftRepository.save(shift);
    }

    @Put("/{id}")
    public Shift updateShift(@PathVariable UUID id, @Valid @Body Shift shift) {
        if (!shiftRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Shift not found");
        }
        return shiftRepository.update(shift.withId(id));
    }

    @Delete("/{id}")
    public void deleteShift(@PathVariable UUID id) {
        shiftRepository.deleteById(id);
    }
}
