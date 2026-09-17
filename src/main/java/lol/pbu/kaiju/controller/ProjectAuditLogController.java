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
import lol.pbu.kaiju.domain.ProjectAuditLog;
import lol.pbu.kaiju.repository.ProjectAuditLogRepository;

import java.util.Optional;
import java.util.UUID;
import static io.micronaut.http.HttpStatus.NOT_FOUND;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("isAuthenticated()")
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

    /**
     * Updates an existing project audit log by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the project audit log does not exist.
     *
     * @param id  the ID of the project audit log to update
     * @param log the updated project audit log details
     * @return the updated project audit log
     */
    @Put("/{id}")
    public ProjectAuditLog updateProjectAuditLog(@PathVariable UUID id, @Valid @Body ProjectAuditLog log) {
        if (!projectAuditLogRepository.existsById(id)) {
            throw new HttpStatusException(NOT_FOUND, "Project audit log not found");
        }
        return projectAuditLogRepository.update(log.withId(id));
    }


    /**
     * Deletes a project audit log by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the project audit log does not exist.
     *
     * @param id the ID of the project audit log to delete
     */
    @Delete("/{id}")
    public void deleteProjectAuditLog(@PathVariable UUID id) {
        if (!projectAuditLogRepository.existsById(id)) {
            throw new HttpStatusException(NOT_FOUND, "Project audit log not found");
        }
        projectAuditLogRepository.deleteById(id);
    }

}
