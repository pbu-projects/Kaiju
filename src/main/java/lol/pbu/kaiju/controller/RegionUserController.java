package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
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
import lol.pbu.kaiju.domain.RegionUser;
import lol.pbu.kaiju.domain.RegionUserId;
import lol.pbu.kaiju.dto.CreateRegionUserCommand;
import lol.pbu.kaiju.dto.UpdateRegionUserCommand;
import lol.pbu.kaiju.repository.RegionUserRepository;
import lol.pbu.kaiju.util.PageableUtils;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/region-users")
public class RegionUserController {

    public static final String DEFAULT_SORT_FIELD = "id.userId";

    private final RegionUserRepository regionUserRepository;

    public RegionUserController(RegionUserRepository regionUserRepository) {
        this.regionUserRepository = regionUserRepository;
    }

    @Get
    public CursoredPage<RegionUser> getRegionUsers(@Nullable @Valid CursoredPageable pageable) {
        return regionUserRepository.findAll(PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    @Get("/{userId}/{regionId}")
    public Optional<RegionUser> getRegionUser(@PathVariable UUID userId, @PathVariable UUID regionId) {
        return regionUserRepository.findById(new RegionUserId(userId, regionId));
    }

    @Post
    public RegionUser addRegionUser(@Valid @Body CreateRegionUserCommand command) {
        RegionUserId id = new RegionUserId(command.userId(), command.regionId());
        return regionUserRepository.save(new RegionUser(id, command.role()));
    }

    @Put("/{userId}/{regionId}")
    public RegionUser updateRegionUser(
            @PathVariable UUID userId,
            @PathVariable UUID regionId,
            @Valid @Body UpdateRegionUserCommand command
    ) {
        RegionUserId id = new RegionUserId(userId, regionId);
        regionUserRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Region user not found"));
        return regionUserRepository.update(new RegionUser(id, command.role()));
    }

    @Delete("/{userId}/{regionId}")
    public void deleteRegionUser(@PathVariable UUID userId, @PathVariable UUID regionId) {
        RegionUserId id = new RegionUserId(userId, regionId);
        long deletedCount = regionUserRepository.removeById(id);
        if (deletedCount == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Region user not found");
        }
    }
}
