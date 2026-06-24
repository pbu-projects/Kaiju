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

    /**
     * Updates an existing organization by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the organization does not exist.
     * Refer to the sister method {@link #updateOrganizationNoLook(UUID, Organization)} to update without validation.
     *
     * @param id           the ID of the organization to update
     * @param organization the updated organization details
     * @return the updated organization
     */
    @Put("/{id}")
    public Organization updateOrganization(@PathVariable UUID id, @Valid @Body Organization organization) {
        if (!organizationRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Organization not found");
        }
        return organizationRepository.update(organization.withId(id));
    }

    /**
     * Updates an organization by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #updateOrganization(UUID, Organization)} to update with existence validation.
     *
     * @param id           the ID of the organization to update
     * @param organization the updated organization details
     * @return the updated organization
     */
    @Put("/{id}/no-look")
    public Organization updateOrganizationNoLook(@PathVariable UUID id, @Valid @Body Organization organization) {
        return organizationRepository.update(organization.withId(id));
    }

    /**
     * Deletes an organization by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the organization does not exist.
     * Refer to the sister method {@link #deleteOrganizationNoLook(UUID)} to delete without validation.
     *
     * @param id the ID of the organization to delete
     */
    @Delete("/{id}")
    public void deleteOrganization(@PathVariable UUID id) {
        if (!organizationRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Organization not found");
        }
        organizationRepository.deleteById(id);
    }

    /**
     * Deletes an organization by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #deleteOrganization(UUID)} to delete with existence validation.
     *
     * @param id the ID of the organization to delete
     */
    @Delete("/{id}/no-look")
    public void deleteOrganizationNoLook(@PathVariable UUID id) {
        organizationRepository.deleteById(id);
    }
}
