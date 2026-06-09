package lol.pbu.kaiju.core.controller;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.http.annotation.Controller;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import lol.pbu.kaiju.core.domain.Shift;
import lol.pbu.kaiju.core.repository.ShiftRepository;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.PathVariable;

import java.util.Optional;
import java.util.UUID;
import jakarta.validation.Valid;

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
    public Optional<Shift> getShift(@Id @PathVariable UUID id) {
        return shiftRepository.findById(id);
    }

    @Post
    public Shift addShift(@Valid @Body Shift shift) {
        return shiftRepository.save(shift);
    }

    @Put("/{id}")
    public Shift updateShift(@Id @PathVariable UUID id, @Valid @Body Shift shift) {
        var shiftWithId = new Shift(
            id,
            shift.locationId(),
            shift.name(),
            shift.startTime(),
            shift.endTime()
        );
        return shiftRepository.update(shiftWithId);
    }

    @Delete("/{id}")
    public void deleteShift(@Id @PathVariable UUID id) {
        shiftRepository.deleteById(id);
    }
}