package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.RegionUser;
import lol.pbu.kaiju.core.domain.RegionUserId;
import lol.pbu.kaiju.core.repository.RegionUserRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/region-users")
public class RegionUserController {

    private final RegionUserRepository regionUserRepository;

    public RegionUserController(RegionUserRepository regionUserRepository) {
        this.regionUserRepository = regionUserRepository;
    }

    @Get
    public CursoredPage<RegionUser> getRegionUsers(@Valid CursoredPageable pageable) {
        return regionUserRepository.findAll(pageable);
    }

    @Get("/{userId}/{regionId}")
    public Optional<RegionUser> getRegionUser(@PathVariable UUID userId, @PathVariable UUID regionId) {
        return regionUserRepository.findById(new RegionUserId(userId, regionId));
    }

    @Post
    public RegionUser addRegionUser(@Valid @Body RegionUser user) {
        return regionUserRepository.save(user);
    }

    @Put("/{userId}/{regionId}")
    public RegionUser updateRegionUser(@PathVariable UUID userId, @PathVariable UUID regionId, @Valid @Body RegionUser user) {
        RegionUserId id = new RegionUserId(userId, regionId);
        if (!regionUserRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Region user not found");
        }
        return regionUserRepository.update(user.withId(id));
    }

    @Put("/{userId}/{regionId}/no-look")
    public RegionUser updateRegionUserNoLook(@PathVariable UUID userId, @PathVariable UUID regionId, @Valid @Body RegionUser user) {
        return regionUserRepository.update(user.withId(new RegionUserId(userId, regionId)));
    }

    @Delete("/{userId}/{regionId}")
    public void deleteRegionUser(@PathVariable UUID userId, @PathVariable UUID regionId) {
        RegionUserId id = new RegionUserId(userId, regionId);
        if (!regionUserRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Region user not found");
        }
        regionUserRepository.deleteById(id);
    }

    @Delete("/{userId}/{regionId}/no-look")
    public void deleteRegionUserNoLook(@PathVariable UUID userId, @PathVariable UUID regionId) {
        regionUserRepository.deleteById(new RegionUserId(userId, regionId));
    }
}
