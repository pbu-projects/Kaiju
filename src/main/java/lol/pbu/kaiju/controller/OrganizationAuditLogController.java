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
import lol.pbu.kaiju.domain.OrganizationAuditLog;
import lol.pbu.kaiju.repository.OrganizationAuditLogRepository;

import java.util.Optional;
import java.util.UUID;
import static io.micronaut.http.HttpStatus.NOT_FOUND;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("isAuthenticated()")
@Controller("/organization-audit-logs")
public class OrganizationAuditLogController implements ExistenceValidator {

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
        checkExists(organizationAuditLogRepository, id);
        return organizationAuditLogRepository.update(log.withId(id));
    }

    @Delete("/{id}")
    public void deleteOrganizationAuditLog(@PathVariable UUID id) {
        checkExists(organizationAuditLogRepository, id);
        organizationAuditLogRepository.deleteById(id);
    }
}
