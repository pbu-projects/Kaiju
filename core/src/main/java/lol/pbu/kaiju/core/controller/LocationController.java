package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.annotation.Controller;
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

import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/locations")
public class LocationController {

    private final LocationRepository locationRepository;

    public LocationController(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    @Get
    public Page<Location> getLocations(@Valid Pageable pageable) {
        return locationRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Location getLocation(@PathVariable UUID id) {
        return locationRepository.findById(id).orElse(null);
    }

    @Post
    public Location addLocation(@Valid @Body Location location) {
        return locationRepository.save(location);
    }

    @Put("/{id}")
    public Location updateLocation(@PathVariable UUID id, @Valid @Body Location location) {
        return locationRepository.update(location);
    }

    @Delete("/{id}")
    public void deleteLocation(@PathVariable UUID id) {
        locationRepository.deleteById(id);
    }
}