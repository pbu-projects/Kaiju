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
import lol.pbu.kaiju.domain.Shift;
import lol.pbu.kaiju.repository.ShiftRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/shifts")
public class ShiftController implements ExistenceValidator {

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
        
        // We explicitly instantiate a new Shift rather than using a wither method.
        // This manual mapping prevents mass-assignment vulnerabilities. If a protected
        // field (like createdAt) is added later, a wither method would blindly copy
        // the user's unvalidated payload.
        // We explicitly instantiate a new Shift rather than using a wither method.
        // This manual mapping prevents mass-assignment vulnerabilities. If a protected
        // field (like createdAt) is added later, a wither method would blindly copy
        // the user's unvalidated payload.
        Shift securePayload = new Shift(
                id,
                shift.project(),
                shift.isVirtual(),
                shift.location(),
                shift.startTime(),
                shift.endTime(),
                shift.tags()
        );
        return shiftRepository.update(securePayload);
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
