package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Location;
import lol.pbu.kaiju.core.repository.LocationRepository;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.PathVariable;

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

    @Put("/{id}")
    public Location updateLocation(@PathVariable UUID id, @Valid @Body Location location) {
        if (!locationRepository.existsById(id)) {
            throw new HttpStatusException(NOT_FOUND, "Location not found");
        }
        var locationWithId = new Location(
            id,
            location.name(),
            location.addressLine(),
            location.city(),
            location.stateProvince(),
            location.postalCode(),
            location.countryCode(),
            location.geom()
        );
        return locationRepository.update(locationWithId);
    }

    @Delete("/{id}")
    public void deleteById(@PathVariable UUID id) {
        locationRepository.deleteById(id);
    }
}