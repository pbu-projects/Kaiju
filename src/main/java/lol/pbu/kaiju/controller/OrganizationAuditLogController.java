package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.OrganizationAuditLog;
import lol.pbu.kaiju.repository.OrganizationAuditLogRepository;
import lol.pbu.kaiju.util.ControllerUtils;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.scheduling.TaskExecutors.BLOCKING;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/organization-audit-logs")
public class OrganizationAuditLogController implements ControllerUtils {

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
