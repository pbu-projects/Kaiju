package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.dto.CreateLocationCommand;
import lol.pbu.kaiju.dto.UpdateLocationCommand;
import lol.pbu.kaiju.repository.LocationRepository;
import lol.pbu.kaiju.security.AuthorizationService;
import lol.pbu.kaiju.util.PageRequests;
import lol.pbu.kaiju.util.SpatialMappingService;
import org.locationtech.jts.geom.Point;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.security.Permission.REGION_MANAGE_CLAIM;
import static lol.pbu.kaiju.security.Permission.SYSTEM_ADMIN_CLAIM;

/**
 * REST controller for managing locations.
 */
@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/locations")
public class LocationController {

    public static final String DEFAULT_SORT_FIELD = "name";
    private static final String LOCATION_NOT_FOUND = "Location not found";

    private final LocationRepository locationRepository;
    private final SpatialMappingService spatialMappingService;
    private final AuthorizationService authorizationService;

    public LocationController(
            LocationRepository locationRepository,
            SpatialMappingService spatialMappingService,
            AuthorizationService authorizationService
    ) {
        this.locationRepository = locationRepository;
        this.spatialMappingService = spatialMappingService;
        this.authorizationService = authorizationService;
    }

    @Get
    public CursoredPage<Location> getLocations(@Nullable @Valid CursoredPageable pageable) {
        return locationRepository.findAll(PageRequests.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    @Get("/{id}")
    public Optional<Location> getLocation(@PathVariable UUID id) {
        return locationRepository.findById(id);
    }

    /**
     * Creates a new location.
     * Restricted to platform administrators and directors of the enclosing region.
     *
     * @param command   the payload containing location details
     * @param principal the authenticated caller
     * @return the persisted location
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Post
    public Location addLocation(@Valid @Body CreateLocationCommand command, Principal principal) {
        Point point = spatialMappingService.toPoint(command.longitude(), command.latitude());
        authorizationService.authorizeLocationCreate(UUID.fromString(principal.getName()), point);
        Location location = new Location(
                null,
                command.name(),
                command.addressLine(),
                command.city(),
                command.stateProvince(),
                command.postalCode(),
                command.countryCode(),
                point,
                resolveTimezone(point, command.timezone())
        );
        return locationRepository.save(location);
    }

    /**
     * Updates an existing location after checking caller permission and region access.
     *
     * @param id        the ID of the location to update
     * @param command   the updated location details
     * @param principal the authenticated caller
     * @return the updated location
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Put("/{id}")
    public Location updateLocation(
            @PathVariable UUID id,
            @Valid @Body UpdateLocationCommand command,
            Principal principal
    ) {
        Point point = spatialMappingService.toPoint(command.longitude(), command.latitude());
        authorizationService.authorizeLocationUpdate(UUID.fromString(principal.getName()), id, point);
        Location existing = locationRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, LOCATION_NOT_FOUND));
        String timezone = resolveUpdatedTimezone(existing, point, command.timezone());
        Location location = new Location(
                id,
                command.name(),
                command.addressLine(),
                command.city(),
                command.stateProvince(),
                command.postalCode(),
                command.countryCode(),
                point,
                timezone
        );
        return locationRepository.update(location);
    }

    /**
     * Deletes a location by its ID after checking caller permission and region access.
     *
     * @param id        the ID of the location to delete
     * @param principal the authenticated caller
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Delete("/{id}")
    public void deleteById(@PathVariable UUID id, Principal principal) {
        UUID actorId = UUID.fromString(principal.getName());
        authorizationService.authorizeLocationDelete(actorId, id);
        long deletedCount = locationRepository.removeById(id);
        if (deletedCount == 0) {
            throw new HttpStatusException(NOT_FOUND, LOCATION_NOT_FOUND);
        }
    }

    private String resolveUpdatedTimezone(Location existing, Point point, String requestedTimezone) {
        if (existing.geom() != null && existing.geom().equalsExact(point) && existing.timezone() != null) {
            return requestedTimezone != null ? requestedTimezone : existing.timezone();
        }
        return resolveTimezone(point, requestedTimezone);
    }

    String resolveTimezone(Point point, String fallbackTimezone) {
        if (point == null) {
            return fallbackTimezone;
        }
        return locationRepository.findTimezoneByPoint(point)
                .orElse(fallbackTimezone);
    }
}
