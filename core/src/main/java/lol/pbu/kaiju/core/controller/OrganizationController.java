package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Organization;
import lol.pbu.kaiju.core.model.OrganizationRequest;
import lol.pbu.kaiju.core.model.OrganizationResponse;
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
    public Page<OrganizationResponse> list(@Valid Pageable pageable) {
        return organizationRepository.findAll(pageable).map(this::toResponse);
    }

    @Get("/{id}")
    public Optional<OrganizationResponse> get(@PathVariable UUID id) {
        return organizationRepository.findById(id).map(this::toResponse);
    }

    @Post
    @Status(HttpStatus.CREATED)
    public OrganizationResponse create(@Valid @Body OrganizationRequest request) {
        Organization organization = new Organization(
                null,
                request.name(),
                request.websiteUrl(),
                request.parentId() != null ? new Organization(request.parentId(), null, null, null) : null
        );
        return toResponse(organizationRepository.save(organization));
    }

    @Put("/{id}")
    public OrganizationResponse update(@PathVariable UUID id, @Valid @Body OrganizationRequest request) {
        Organization organization = new Organization(
                id,
                request.name(),
                request.websiteUrl(),
                request.parentId() != null ? new Organization(request.parentId(), null, null, null) : null
        );
        return toResponse(organizationRepository.update(organization));
    }

    @Delete("/{id}")
    @Status(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        organizationRepository.deleteById(id);
    }

    private OrganizationResponse toResponse(Organization organization) {
        return new OrganizationResponse(
                organization.id(),
                organization.name(),
                organization.websiteUrl(),
                organization.parent() != null ? organization.parent().id() : null
        );
    }
}
