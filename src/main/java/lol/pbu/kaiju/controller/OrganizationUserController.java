package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.OrganizationUser;
import lol.pbu.kaiju.domain.OrganizationUserId;
import lol.pbu.kaiju.repository.OrganizationUserRepository;
import lol.pbu.kaiju.repository.SecurityQueryRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.Permission;
import lol.pbu.kaiju.util.ControllerUtils;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.BAD_REQUEST;
import static io.micronaut.http.HttpStatus.FORBIDDEN;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/organization-users")
public class OrganizationUserController implements ControllerUtils {

    private final OrganizationUserRepository organizationUserRepository;
    private final SecurityQueryRepository queryRepository;
    private final UserRepository userRepository;

    public OrganizationUserController(
            OrganizationUserRepository organizationUserRepository,
            SecurityQueryRepository queryRepository,
            UserRepository userRepository
    ) {
        this.organizationUserRepository = organizationUserRepository;
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
    }

    @Get
    public CursoredPage<OrganizationUser> getOrganizationUsers(@Valid CursoredPageable pageable) {
        return organizationUserRepository.findAll(pageable);
    }

    @Get("/{userId}/{organizationId}")
    public Optional<OrganizationUser> getOrganizationUser(@PathVariable UUID userId, @PathVariable UUID organizationId) {
        return organizationUserRepository.findById(new OrganizationUserId(userId, organizationId));
    }

    @Post
    public OrganizationUser addOrganizationUser(@Valid @Body OrganizationUser user, Principal principal) {
        if (user.id() == null || user.id().organizationId() == null) {
            throw new HttpStatusException(BAD_REQUEST, "Organization ID is required");
        }
        verifyOrgAdminAuthority(principal, user.id().organizationId());
        return organizationUserRepository.save(user);
    }

    @Put("/{userId}/{organizationId}")
    public OrganizationUser updateOrganizationUser(
            @PathVariable UUID userId,
            @PathVariable UUID organizationId,
            @Valid @Body OrganizationUser user,
            Principal principal
    ) {
        verifyOrgAdminAuthority(principal, organizationId);
        OrganizationUserId id = new OrganizationUserId(userId, organizationId);
        checkExists(organizationUserRepository, id);
        return organizationUserRepository.update(user.withId(id));
    }

    @Delete("/{userId}/{organizationId}")
    public void deleteOrganizationUser(
            @PathVariable UUID userId,
            @PathVariable UUID organizationId,
            Principal principal
    ) {
        verifyOrgAdminAuthority(principal, organizationId);
        OrganizationUserId id = new OrganizationUserId(userId, organizationId);
        checkExists(organizationUserRepository, id);
        organizationUserRepository.deleteById(id);
    }

    private void verifyOrgAdminAuthority(Principal principal, UUID organizationId) {
        UUID callerId = UUID.fromString(principal.getName());
        boolean isSysAdmin = userRepository.findById(callerId)
                .map(u -> u.role().hasPermission(Permission.SYSTEM_ADMIN) || u.role().hasPermission(Permission.ORG_MANAGE_USERS))
                .orElse(false);
        if (isSysAdmin) {
            return;
        }
        if (!queryRepository.isOrgAdmin(callerId, organizationId)) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: Only organization admins or system administrators may manage organization members");
        }
    }
}
