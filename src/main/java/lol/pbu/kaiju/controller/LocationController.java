package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Sort;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.dto.CreateLocationCommand;
import lol.pbu.kaiju.dto.UpdateLocationCommand;
import lol.pbu.kaiju.repository.LocationRepository;
import lol.pbu.kaiju.util.ControllerUtils;
import lol.pbu.kaiju.util.SpatialMappingService;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/locations")
public class LocationController implements ControllerUtils {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final String DEFAULT_SORT_FIELD = "name";

    private final LocationRepository locationRepository;
    private final SpatialMappingService spatialMappingService;

    public LocationController(LocationRepository locationRepository, SpatialMappingService spatialMappingService) {
        this.locationRepository = locationRepository;
        this.spatialMappingService = spatialMappingService;
    }

    @Get
    public CursoredPage<Location> getLocations(@Nullable @Valid CursoredPageable pageable) {
        CursoredPageable effectivePageable = (pageable == null || pageable.isUnpaged())
                ? CursoredPageable.from(DEFAULT_PAGE_SIZE, Sort.of(Sort.Order.asc(DEFAULT_SORT_FIELD)))
                : pageable;
        return locationRepository.findAll(effectivePageable);
    }

    @Get("/{id}")
    public Optional<Location> getLocation(@PathVariable UUID id) {
        return locationRepository.findById(id);
    }

    @Post
    public Location addLocation(@Valid @Body CreateLocationCommand command) {
        Location location = new Location(
                null,
                command.name(),
                command.addressLine(),
                command.city(),
                command.stateProvince(),
                command.postalCode(),
                command.countryCode(),
                spatialMappingService.toPoint(command.longitude(), command.latitude())
        );
        return locationRepository.save(location);
    }

    /**
     * Updates an existing location by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the location does not exist.
     *
     * @param id      the ID of the location to update
     * @param command the updated location details
     * @return the updated location
     */
    @Put("/{id}")
    public Location updateLocation(@PathVariable UUID id, @Valid @Body UpdateLocationCommand command) {
        checkExists(locationRepository, id);
        Location location = new Location(
                id,
                command.name(),
                command.addressLine(),
                command.city(),
                command.stateProvince(),
                command.postalCode(),
                command.countryCode(),
                spatialMappingService.toPoint(command.longitude(), command.latitude())
        );
        return locationRepository.update(location);
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