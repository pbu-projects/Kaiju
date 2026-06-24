package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Location;
import lol.pbu.kaiju.core.repository.LocationRepository;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.NOT_FOUND;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/locations")
public class LocationController {

    private final LocationRepository locationRepository;

    public LocationController(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    @Get
    public CursoredPage<Location> getLocations(@Valid CursoredPageable pageable) {
        return locationRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Optional<Location> getLocation(@PathVariable UUID id) {
        return locationRepository.findById(id);
    }

    @Post
    public Location addLocation(@Valid @Body Location location) {
        return locationRepository.save(location);
    }

    /**
     * Updates an existing location by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the location does not exist.
     * Refer to the sister method {@link #updateLocationNoLook(UUID, Location)} to update without validation.
     *
     * @param id       the ID of the location to update
     * @param location the updated location details
     * @return the updated location
     */
    @Put("/{id}")
    public Location updateLocation(@PathVariable UUID id, @Valid @Body Location location) {
        if (!locationRepository.existsById(id)) {
            throw new HttpStatusException(NOT_FOUND, "Location not found");
        }
        return locationRepository.update(location.withId(id));
    }

    /**
     * Updates a location by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #updateLocation(UUID, Location)} to update with existence validation.
     *
     * @param id       the ID of the location to update
     * @param location the updated location details
     * @return the updated location
     */
    @Put("/{id}/no-look")
    public Location updateLocationNoLook(@PathVariable UUID id, @Valid @Body Location location) {
        return locationRepository.update(location.withId(id));
    }

    /**
     * Deletes a location by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the location does not exist.
     * Refer to the sister method {@link #deleteByIdNoLook(UUID)} to delete without validation.
     *
     * @param id the ID of the location to delete
     */
    @Delete("/{id}")
    public void deleteById(@PathVariable UUID id) {
        if (!locationRepository.existsById(id)) {
            throw new HttpStatusException(NOT_FOUND, "Location not found");
        }
        locationRepository.deleteById(id);
    }

    /**
     * Deletes a location by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #deleteById(UUID)} to delete with existence validation.
     *
     * @param id the ID of the location to delete
     */
    @Delete("/{id}/no-look")
    public void deleteByIdNoLook(@PathVariable UUID id) {
        locationRepository.deleteById(id);
    }
}