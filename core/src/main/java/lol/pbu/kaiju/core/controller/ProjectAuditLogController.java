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

    /**
     * Updates an existing project audit log by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the project audit log does not exist.
     * Refer to the sister method {@link #updateProjectAuditLogNoLook(UUID, ProjectAuditLog)} to update without validation.
     *
     * @param id  the ID of the project audit log to update
     * @param log the updated project audit log details
     * @return the updated project audit log
     */
    @Put("/{id}")
    public ProjectAuditLog updateProjectAuditLog(@PathVariable UUID id, @Valid @Body ProjectAuditLog log) {
        if (!projectAuditLogRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Project audit log not found");
        }
        return projectAuditLogRepository.update(log.withId(id));
    }

    /**
     * Updates a project audit log by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #updateProjectAuditLog(UUID, ProjectAuditLog)} to update with existence validation.
     *
     * @param id  the ID of the project audit log to update
     * @param log the updated project audit log details
     * @return the updated project audit log
     */
    @Put("/{id}/no-look")
    public ProjectAuditLog updateProjectAuditLogNoLook(@PathVariable UUID id, @Valid @Body ProjectAuditLog log) {
        return projectAuditLogRepository.update(log.withId(id));
    }

    /**
     * Deletes a project audit log by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the project audit log does not exist.
     * Refer to the sister method {@link #deleteProjectAuditLogNoLook(UUID)} to delete without validation.
     *
     * @param id the ID of the project audit log to delete
     */
    @Delete("/{id}")
    public void deleteProjectAuditLog(@PathVariable UUID id) {
        if (!projectAuditLogRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Project audit log not found");
        }
        projectAuditLogRepository.deleteById(id);
    }

    /**
     * Deletes a project audit log by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #deleteProjectAuditLog(UUID)} to delete with existence validation.
     *
     * @param id the ID of the project audit log to delete
     */
    @Delete("/{id}/no-look")
    public void deleteProjectAuditLogNoLook(@PathVariable UUID id) {
        projectAuditLogRepository.deleteById(id);
    }
}
