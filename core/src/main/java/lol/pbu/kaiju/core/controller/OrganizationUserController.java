package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.OrganizationUser;
import lol.pbu.kaiju.core.domain.OrganizationUserId;
import lol.pbu.kaiju.core.repository.OrganizationUserRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/organization-users")
public class OrganizationUserController {

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
        if (!organizationUserRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Organization user not found");
        }
        return organizationUserRepository.update(user.withId(id));
    }

    @Put("/{userId}/{organizationId}/no-look")
    public OrganizationUser updateOrganizationUserNoLook(@PathVariable UUID userId, @PathVariable UUID organizationId, @Valid @Body OrganizationUser user) {
        return organizationUserRepository.update(user.withId(new OrganizationUserId(userId, organizationId)));
    }

    @Delete("/{userId}/{organizationId}")
    public void deleteOrganizationUser(@PathVariable UUID userId, @PathVariable UUID organizationId) {
        OrganizationUserId id = new OrganizationUserId(userId, organizationId);
        if (!organizationUserRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Organization user not found");
        }
        organizationUserRepository.deleteById(id);
    }

    @Delete("/{userId}/{organizationId}/no-look")
    public void deleteOrganizationUserNoLook(@PathVariable UUID userId, @PathVariable UUID organizationId) {
        organizationUserRepository.deleteById(new OrganizationUserId(userId, organizationId));
    }
}
