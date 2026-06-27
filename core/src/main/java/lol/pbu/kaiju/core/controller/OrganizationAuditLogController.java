package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.OrganizationAuditLog;
import lol.pbu.kaiju.core.repository.OrganizationAuditLogRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/organization-audit-logs")
public class OrganizationAuditLogController {

    private final OrganizationAuditLogRepository organizationAuditLogRepository;

    public OrganizationAuditLogController(OrganizationAuditLogRepository organizationAuditLogRepository) {
        this.organizationAuditLogRepository = organizationAuditLogRepository;
    }

    @Get
    public CursoredPage<OrganizationAuditLog> getOrganizationAuditLogs(@Valid CursoredPageable pageable) {
        return organizationAuditLogRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Optional<OrganizationAuditLog> getOrganizationAuditLog(@PathVariable UUID id) {
        return organizationAuditLogRepository.findById(id);
    }

    @Post
    public OrganizationAuditLog addOrganizationAuditLog(@Valid @Body OrganizationAuditLog log) {
        return organizationAuditLogRepository.save(log);
    }

    @Put("/{id}")
    public OrganizationAuditLog updateOrganizationAuditLog(@PathVariable UUID id, @Valid @Body OrganizationAuditLog log) {
        if (!organizationAuditLogRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Organization audit log not found");
        }
        return organizationAuditLogRepository.update(log.withId(id));
    }

    @Put("/{id}/no-look")
    public OrganizationAuditLog updateOrganizationAuditLogNoLook(@PathVariable UUID id, @Valid @Body OrganizationAuditLog log) {
        return organizationAuditLogRepository.update(log.withId(id));
    }

    @Delete("/{id}")
    public void deleteOrganizationAuditLog(@PathVariable UUID id) {
        if (!organizationAuditLogRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Organization audit log not found");
        }
        organizationAuditLogRepository.deleteById(id);
    }

    @Delete("/{id}/no-look")
    public void deleteOrganizationAuditLogNoLook(@PathVariable UUID id) {
        organizationAuditLogRepository.deleteById(id);
    }
}
