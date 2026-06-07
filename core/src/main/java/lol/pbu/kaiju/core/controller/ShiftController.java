package lol.pbu.kaiju.core.controller;

import io.micronaut.http.annotation.Controller;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
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
    public Page<Shift> getShifts(@Valid Pageable pageable) {
        return shiftRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Shift getShift(@PathVariable UUID id) {
        return shiftRepository.findById(id).orElse(null);
    }

    @Post
    public Shift addShift(@Valid @Body Shift shift) {
        return shiftRepository.save(shift);
    }

    @Put("/{id}")
    public Shift updateShift(@PathVariable UUID id, @Valid @Body Shift shift) {
        return shiftRepository.update(shift);
    }

    @Delete("/{id}")
    public void deleteShift(@PathVariable UUID id) {
        shiftRepository.deleteById(id);
    }
}