package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.ProjectAuditLog;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.dto.CreateProjectAuditLogCommand;
import lol.pbu.kaiju.dto.UpdateProjectAuditLogCommand;
import lol.pbu.kaiju.repository.ProjectAuditLogRepository;
import lol.pbu.kaiju.repository.ProjectRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.util.ControllerUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/project-audit-logs")
public class ProjectAuditLogController implements ControllerUtils {

    public static final String DEFAULT_SORT_FIELD = "id";

    private final ProjectAuditLogRepository projectAuditLogRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public ProjectAuditLogController(
            ProjectAuditLogRepository projectAuditLogRepository,
            ProjectRepository projectRepository,
            UserRepository userRepository
    ) {
        this.projectAuditLogRepository = projectAuditLogRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    @Get
    public CursoredPage<ProjectAuditLog> getProjectAuditLogs(@Nullable @Valid CursoredPageable pageable) {
        return projectAuditLogRepository.findAll(resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    @Get("/{id}")
    public Optional<ProjectAuditLog> getProjectAuditLog(@PathVariable UUID id) {
        return projectAuditLogRepository.findById(id);
    }

    @Post
    public ProjectAuditLog addProjectAuditLog(@Valid @Body CreateProjectAuditLogCommand command) {
        Project project = projectRepository.findById(command.projectId())
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Project not found"));
        User actor = userRepository.findById(command.actorId())
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Actor not found"));
        ProjectAuditLog log = new ProjectAuditLog(
                null,
                project,
                actor,
                command.action(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return projectAuditLogRepository.save(log);
    }

    /**
     * Updates an existing project audit log by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the project audit log does not exist.
     *
     * @param id      the ID of the project audit log to update
     * @param command the updated project audit log details
     * @return the updated project audit log
     */
    @Put("/{id}")
    public ProjectAuditLog updateProjectAuditLog(@PathVariable UUID id, @Valid @Body UpdateProjectAuditLogCommand command) {
        checkExists(projectAuditLogRepository, id);
        ProjectAuditLog existing = projectAuditLogRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Project audit log not found"));
        ProjectAuditLog updated = new ProjectAuditLog(
                id,
                existing.project(),
                existing.actor(),
                command.action(),
                existing.createdAt()
        );
        return projectAuditLogRepository.update(updated);
    }


    /**
     * Deletes a project audit log by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the project audit log does not exist.
     *
     * @param id the ID of the project audit log to delete
     */
    @Delete("/{id}")
    public void deleteProjectAuditLog(@PathVariable UUID id) {
        checkExists(projectAuditLogRepository, id);
        projectAuditLogRepository.deleteById(id);
    }

}
