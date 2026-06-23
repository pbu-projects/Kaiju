package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Organization;
import lol.pbu.kaiju.core.repository.OrganizationRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/organizations")
public class OrganizationController {

    private final OrganizationRepository organizationRepository;

    public OrganizationController(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    @Get
    public CursoredPage<Organization> getOrganizations(@Valid CursoredPageable pageable) {
        return organizationRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Optional<Organization> getOrganization(@PathVariable UUID id) {
        return organizationRepository.findById(id);
    }

    @Post
    public Organization addOrganization(@Valid @Body Organization organization) {
        return organizationRepository.save(organization);
    }

    @Put("/{id}")
    public Organization updateOrganization(@PathVariable UUID id, @Valid @Body Organization organization) {
        if (!organizationRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Organization not found");
        }
        return organizationRepository.update(organization.withId(id));
    }

    @Delete("/{id}")
    public void deleteOrganization(@PathVariable UUID id) {
        organizationRepository.deleteById(id);
    }
}
