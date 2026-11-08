package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.AdministrativeRegionAuditLog;
import lol.pbu.kaiju.repository.AdministrativeRegionAuditLogRepository;
import lol.pbu.kaiju.util.PageableUtils;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/administrative-region-audit-logs")
public class AdministrativeRegionAuditLogController {

    public static final String DEFAULT_SORT_FIELD = "createdAt";

    private final AdministrativeRegionAuditLogRepository auditLogRepository;

    public AdministrativeRegionAuditLogController(AdministrativeRegionAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Get("{?regionId}")
    public CursoredPage<AdministrativeRegionAuditLog> getAdministrativeRegionAuditLogs(
            @Nullable @QueryValue UUID regionId,
            @Nullable @Valid CursoredPageable pageable
    ) {
        CursoredPageable resolved = PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD);
        if (regionId != null) {
            return auditLogRepository.findByRegionId(regionId, resolved);
        }
        return auditLogRepository.findAll(resolved);
    }

    @Get("/{id}")
    public Optional<AdministrativeRegionAuditLog> getAdministrativeRegionAuditLog(@PathVariable UUID id) {
        return auditLogRepository.findById(id);
    }
}
