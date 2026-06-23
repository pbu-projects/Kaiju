package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.ProjectAuditLog;
import lol.pbu.kaiju.core.repository.ProjectAuditLogRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/project-audit-logs")
public class ProjectAuditLogController {

    private final ProjectAuditLogRepository projectAuditLogRepository;

    public ProjectAuditLogController(ProjectAuditLogRepository projectAuditLogRepository) {
        this.projectAuditLogRepository = projectAuditLogRepository;
    }

    @Get
    public CursoredPage<ProjectAuditLog> getProjectAuditLogs(@Valid CursoredPageable pageable) {
        return projectAuditLogRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Optional<ProjectAuditLog> getProjectAuditLog(@PathVariable UUID id) {
        return projectAuditLogRepository.findById(id);
    }

    @Post
    public ProjectAuditLog addProjectAuditLog(@Valid @Body ProjectAuditLog log) {
        return projectAuditLogRepository.save(log);
    }

    @Put("/{id}")
    public ProjectAuditLog updateProjectAuditLog(@PathVariable UUID id, @Valid @Body ProjectAuditLog log) {
        if (!projectAuditLogRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Project audit log not found");
        }
        return projectAuditLogRepository.update(log.withId(id));
    }

    @Delete("/{id}")
    public void deleteProjectAuditLog(@PathVariable UUID id) {
        projectAuditLogRepository.deleteById(id);
    }
}
