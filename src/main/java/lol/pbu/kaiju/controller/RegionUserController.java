package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.RegionUser;
import lol.pbu.kaiju.domain.RegionUserId;
import lol.pbu.kaiju.repository.RegionUserRepository;

import java.util.Optional;
import java.util.UUID;
import static io.micronaut.http.HttpStatus.NOT_FOUND;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("isAuthenticated()")
@Controller("/region-users")
public class RegionUserController implements ExistenceValidator {

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
        checkExists(regionUserRepository, id);
        return regionUserRepository.update(user.withId(id));
    }

@Delete("/{userId}/{regionId}")
    public void deleteRegionUser(@PathVariable UUID userId, @PathVariable UUID regionId) {
        RegionUserId id = new RegionUserId(userId, regionId);
        checkExists(regionUserRepository, id);
        regionUserRepository.deleteById(id);
    }

}
