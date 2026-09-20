package lol.pbu.kaiju.controller;
import lol.pbu.kaiju.util.ControllerUtils;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.repository.LocationRepository;


import java.util.Optional;
import java.util.UUID;


@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/locations")
public class LocationController implements ControllerUtils {

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
        
        // We explicitly instantiate a new Location rather than using a wither method.
        // This manual mapping prevents mass-assignment vulnerabilities. If a protected
        // field (like createdAt) is added later, a wither method would blindly copy
        // the user's unvalidated payload.
        // We explicitly instantiate a new Location rather than using a wither method.
        // This manual mapping prevents mass-assignment vulnerabilities. If a protected
        // field (like createdAt) is added later, a wither method would blindly copy
        // the user's unvalidated payload.
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