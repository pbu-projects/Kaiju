package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Shift;
import lol.pbu.kaiju.repository.ShiftRepository;
import lol.pbu.kaiju.util.ControllerUtils;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/shifts")
public class ShiftController implements ControllerUtils {

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
     *
     * @param id    the ID of the shift to update
     * @param shift the updated shift details
     * @return the updated shift
     */
    @Put("/{id}")
    public Shift updateShift(@PathVariable UUID id, @Valid @Body Shift shift) {
        checkExists(shiftRepository, id);
        
        return shiftRepository.update(shift.withId(id));
    }


    /**
     * Deletes a shift by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the shift does not exist.
     *
     * @param id the ID of the shift to delete
     */
    @Delete("/{id}")
    public void deleteShift(@PathVariable UUID id) {
        checkExists(shiftRepository, id);
        shiftRepository.deleteById(id);
    }

}
