package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.OrganizationUser;
import lol.pbu.kaiju.domain.OrganizationUserId;
import lol.pbu.kaiju.repository.OrganizationUserRepository;
import lol.pbu.kaiju.util.ControllerUtils;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/organization-users")
public class OrganizationUserController implements ControllerUtils {

    private final OrganizationUserRepository organizationUserRepository;

    public OrganizationUserController(OrganizationUserRepository organizationUserRepository) {
        this.organizationUserRepository = organizationUserRepository;
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
    public OrganizationUser addOrganizationUser(@Valid @Body OrganizationUser user) {
        return organizationUserRepository.save(user);
    }

    @Put("/{userId}/{organizationId}")
    public OrganizationUser updateOrganizationUser(@PathVariable UUID userId, @PathVariable UUID organizationId, @Valid @Body OrganizationUser user) {
        OrganizationUserId id = new OrganizationUserId(userId, organizationId);
        checkExists(organizationUserRepository, id);
        return organizationUserRepository.update(user.withId(id));
    }



    @Delete("/{userId}/{organizationId}")
    public void deleteOrganizationUser(@PathVariable UUID userId, @PathVariable UUID organizationId) {
        OrganizationUserId id = new OrganizationUserId(userId, organizationId);
        checkExists(organizationUserRepository, id);
        organizationUserRepository.deleteById(id);
    }
}
