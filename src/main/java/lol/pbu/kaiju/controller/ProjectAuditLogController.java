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
import lol.pbu.kaiju.domain.ProjectAuditLog;
import lol.pbu.kaiju.repository.ProjectAuditLogRepository;
import lol.pbu.kaiju.util.PageableUtils;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/project-audit-logs")
public class ProjectAuditLogController {

    public static final String DEFAULT_SORT_FIELD = "id";

    private final ProjectAuditLogRepository projectAuditLogRepository;

    public ProjectAuditLogController(
            ProjectAuditLogRepository projectAuditLogRepository
    ) {
        this.projectAuditLogRepository = projectAuditLogRepository;
    }

    @Get
    public CursoredPage<ProjectAuditLog> getProjectAuditLogs(@Nullable @Valid CursoredPageable pageable) {
        return projectAuditLogRepository.findAll(PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    @Get("/{id}")
    public Optional<ProjectAuditLog> getProjectAuditLog(@PathVariable UUID id) {
        return projectAuditLogRepository.findById(id);
    }
}
