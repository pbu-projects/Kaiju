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
import lol.pbu.kaiju.domain.OrganizationUser;
import lol.pbu.kaiju.domain.OrganizationUserId;
import lol.pbu.kaiju.repository.OrganizationUserRepository;

import java.util.Optional;
import java.util.UUID;
import static io.micronaut.http.HttpStatus.NOT_FOUND;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("isAuthenticated()")
@Controller("/organization-users")
public class OrganizationUserController implements ExistenceValidator {

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
