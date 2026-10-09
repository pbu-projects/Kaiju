package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.OrganizationAuditLog;
import lol.pbu.kaiju.repository.OrganizationAuditLogRepository;
import lol.pbu.kaiju.util.PageableUtils;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/organization-audit-logs")
public class OrganizationAuditLogController {

    public static final String DEFAULT_SORT_FIELD = "id";

    private final OrganizationAuditLogRepository organizationAuditLogRepository;

    public OrganizationAuditLogController(
            OrganizationAuditLogRepository organizationAuditLogRepository
    ) {
        this.organizationAuditLogRepository = organizationAuditLogRepository;
    }

    @Get
    public CursoredPage<OrganizationAuditLog> getOrganizationAuditLogs(@Nullable @Valid CursoredPageable pageable) {
        return organizationAuditLogRepository.findAll(PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    @Get("/{id}")
    public Optional<OrganizationAuditLog> getOrganizationAuditLog(@PathVariable UUID id) {
        return organizationAuditLogRepository.findById(id);
    }
}
