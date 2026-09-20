package lol.pbu.kaiju.controller;
import static lol.pbu.kaiju.security.SecurityRoles.IS_AUTHENTICATED;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.repository.OrganizationRepository;

import java.util.Optional;
import java.util.UUID;
import static io.micronaut.http.HttpStatus.NOT_FOUND;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/organizations")
public class OrganizationController implements ExistenceValidator {

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
     *
     * @param id           the ID of the organization to update
     * @param organization the updated organization details
     * @return the updated organization
     */
    @Put("/{id}")
    public Organization updateOrganization(@PathVariable UUID id, @Valid @Body Organization organization) {
        Organization existing = organizationRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Organization not found"));
        
        Organization secureOrganization = new Organization(
                id,
                organization.name(),
                organization.websiteUrl(),
                organization.parentId(),
                organization.isPublic(),
                existing.verificationStatus(),
                existing.verificationExpiresAt(),
                existing.locations()
        );
        return organizationRepository.update(secureOrganization);
    }


    /**
     * Deletes an organization by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the organization does not exist.
     *
     * @param id the ID of the organization to delete
     */
    @Delete("/{id}")
    public void deleteOrganization(@PathVariable UUID id) {
        checkExists(organizationRepository, id);
        organizationRepository.deleteById(id);
    }

}
