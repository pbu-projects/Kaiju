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

    /**
     * Updates an existing shift by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the shift does not exist.
     * Refer to the sister method {@link #updateShiftNoLook(UUID, Shift)} to update without validation.
     *
     * @param id    the ID of the shift to update
     * @param shift the updated shift details
     * @return the updated shift
     */
    @Put("/{id}")
    public Shift updateShift(@PathVariable UUID id, @Valid @Body Shift shift) {
        if (!shiftRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Shift not found");
        }
        return shiftRepository.update(shift.withId(id));
    }

    /**
     * Updates a shift by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #updateShift(UUID, Shift)} to update with existence validation.
     *
     * @param id    the ID of the shift to update
     * @param shift the updated shift details
     * @return the updated shift
     */
    @Put("/{id}/no-look")
    public Shift updateShiftNoLook(@PathVariable UUID id, @Valid @Body Shift shift) {
        return shiftRepository.update(shift.withId(id));
    }

    /**
     * Deletes a shift by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the shift does not exist.
     * Refer to the sister method {@link #deleteShiftNoLook(UUID)} to delete without validation.
     *
     * @param id the ID of the shift to delete
     */
    @Delete("/{id}")
    public void deleteShift(@PathVariable UUID id) {
        if (!shiftRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Shift not found");
        }
        shiftRepository.deleteById(id);
    }

    /**
     * Deletes a shift by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #deleteShift(UUID)} to delete with existence validation.
     *
     * @param id the ID of the shift to delete
     */
    @Delete("/{id}/no-look")
    public void deleteShiftNoLook(@PathVariable UUID id) {
        shiftRepository.deleteById(id);
    }
}
