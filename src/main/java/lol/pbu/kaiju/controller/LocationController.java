package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.repository.LocationRepository;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.NOT_FOUND;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("isAuthenticated()")
@Controller("/locations")
public class LocationController implements ExistenceValidator {

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
     *
     * @param id       the ID of the location to update
     * @param location the updated location details
     * @return the updated location
     */
    @Put("/{id}")
    public Location updateLocation(@PathVariable UUID id, @Valid @Body Location location) {
        checkExists(locationRepository, id);
        
        Location securePayload = new Location(
                id,
                location.name(),
                location.addressLine(),
                location.city(),
                location.stateProvince(),
                location.postalCode(),
                location.countryCode(),
                location.geom()
        );
        return locationRepository.update(securePayload);
    }


    /**
     * Deletes a location by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the location does not exist.
     *
     * @param id the ID of the location to delete
     */
    @Delete("/{id}")
    public void deleteById(@PathVariable UUID id) {
        checkExists(locationRepository, id);
        locationRepository.deleteById(id);
    }

}