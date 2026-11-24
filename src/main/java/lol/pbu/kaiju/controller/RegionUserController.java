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
import lol.pbu.kaiju.domain.RegionUser;
import lol.pbu.kaiju.domain.RegionUserId;
import lol.pbu.kaiju.dto.CreateRegionUserCommand;
import lol.pbu.kaiju.dto.UpdateRegionUserCommand;
import lol.pbu.kaiju.repository.RegionUserRepository;
import lol.pbu.kaiju.security.AuthorizationService;
import lol.pbu.kaiju.util.PageRequests;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.security.Permission.REGION_MANAGE_CLAIM;
import static lol.pbu.kaiju.security.Permission.SYSTEM_ADMIN_CLAIM;

/**
 * REST controller for managing regional user associations and role assignments.
 */
@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/region-users")
public class RegionUserController {

    public static final String DEFAULT_SORT_FIELD = "id.userId";

    private final RegionUserRepository regionUserRepository;
    private final AuthorizationService authorizationService;

    public RegionUserController(
            RegionUserRepository regionUserRepository,
            AuthorizationService authorizationService
    ) {
        this.regionUserRepository = regionUserRepository;
        this.authorizationService = authorizationService;
    }

    /**
     * Retrieves a paginated list of regional user associations.
     *
     * @param pageable pagination and sorting parameters
     * @return a cursored page of {@link RegionUser} records
     */
    @Secured(SYSTEM_ADMIN_CLAIM)
    @Get
    public CursoredPage<RegionUser> getRegionUsers(@Nullable @Valid CursoredPageable pageable) {
        return regionUserRepository.findAll(PageRequests.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    /**
     * Retrieves a specific region-user association by composite identifier.
     *
     * @param userId    the unique identifier of the user
     * @param regionId  the unique identifier of the region
     * @param principal the authenticated caller
     * @return an optional containing the {@link RegionUser} if found
     */
    @Get("/{userId}/{regionId}")
    public Optional<RegionUser> getRegionUser(
            @PathVariable UUID userId,
            @PathVariable UUID regionId,
            Principal principal
    ) {
        UUID actorId = UUID.fromString(principal.getName());
        authorizationService.authorizeRegionUserView(actorId, userId, regionId);
        return regionUserRepository.findById(new RegionUserId(userId, regionId));
    }

    /**
     * Associates a user with an administrative region under a designated role.
     * Restricted to platform administrators and directors of the target region.
     *
     * @param command   the payload containing the target user ID, region ID, and role
     * @param principal the authenticated caller
     * @return the persisted {@link RegionUser} relationship
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Post
    public RegionUser addRegionUser(@Valid @Body CreateRegionUserCommand command, Principal principal) {
        verifyRegionDirectorAuthority(principal, command.regionId());
        RegionUserId id = new RegionUserId(command.userId(), command.regionId());
        return regionUserRepository.save(new RegionUser(id, command.role()));
    }

    /**
     * Updates an existing user role assignment within an administrative region.
     * Restricted to platform administrators and directors of the target region.
     *
     * @param userId    the unique identifier of the user
     * @param regionId  the unique identifier of the region
     * @param command   the payload containing the new role assignment
     * @param principal the authenticated caller
     * @return the updated {@link RegionUser} relationship
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Put("/{userId}/{regionId}")
    public RegionUser updateRegionUser(
            @PathVariable UUID userId,
            @PathVariable UUID regionId,
            @Valid @Body UpdateRegionUserCommand command,
            Principal principal
    ) {
        verifyRegionDirectorAuthority(principal, regionId);
        RegionUserId id = new RegionUserId(userId, regionId);
        regionUserRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Region user not found"));
        return regionUserRepository.update(new RegionUser(id, command.role()));
    }

    /**
     * Removes a user role assignment from an administrative region.
     * Restricted to platform administrators and directors of the target region.
     *
     * @param userId    the unique identifier of the user to remove
     * @param regionId  the unique identifier of the region
     * @param principal the authenticated caller
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Delete("/{userId}/{regionId}")
    public void deleteRegionUser(
            @PathVariable UUID userId,
            @PathVariable UUID regionId,
            Principal principal
    ) {
        verifyRegionDirectorAuthority(principal, regionId);
        RegionUserId id = new RegionUserId(userId, regionId);
        long deletedCount = regionUserRepository.removeById(id);
        if (deletedCount == 0) {
            throw new HttpStatusException(NOT_FOUND, "Region user not found");
        }
    }

    private void verifyRegionDirectorAuthority(Principal principal, UUID regionId) {
        UUID actorId = UUID.fromString(principal.getName());
        authorizationService.authorizeRegionDirector(actorId, regionId);
    }
}
