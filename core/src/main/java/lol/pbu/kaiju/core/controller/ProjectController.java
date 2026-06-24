package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Project;
import lol.pbu.kaiju.core.repository.ProjectRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;

    public ProjectController(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Get
    public CursoredPage<Project> getProjects(String title, @Valid CursoredPageable pageable) {
        return projectRepository.findByTitle(title, pageable);
    }

    @Get("/{id}")
    public Optional<Project> getProject(@PathVariable UUID id) {
        return projectRepository.findById(id);
    }

    @Post
    public Project addProject(@Valid @Body Project project) {
        return projectRepository.save(project);
    }

    /**
     * Updates an existing project by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the project does not exist.
     * Refer to the sister method {@link #updateProjectNoLook(UUID, Project)} to update without validation.
     *
     * @param id      the ID of the project to update
     * @param project the updated project details
     * @return the updated project
     */
    @Put("/{id}")
    public Project updateProject(@PathVariable UUID id, @Valid @Body Project project) {
        if (!projectRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return projectRepository.update(project.withId(id));
    }

    /**
     * Updates a project by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #updateProject(UUID, Project)} to update with existence validation.
     *
     * @param id      the ID of the project to update
     * @param project the updated project details
     * @return the updated project
     */
    @Put("/{id}/no-look")
    public Project updateProjectNoLook(@PathVariable UUID id, @Valid @Body Project project) {
        return projectRepository.update(project.withId(id));
    }

    /**
     * Deletes a project by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the project does not exist.
     * Refer to the sister method {@link #deleteProjectNoLook(UUID)} to delete without validation.
     *
     * @param id the ID of the project to delete
     */
    @Delete("/{id}")
    public void deleteProject(@PathVariable UUID id) {
        if (!projectRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        projectRepository.deleteById(id);
    }

    /**
     * Deletes a project by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #deleteProject(UUID)} to delete with existence validation.
     *
     * @param id the ID of the project to delete
     */
    @Delete("/{id}/no-look")
    public void deleteProjectNoLook(@PathVariable UUID id) {
        projectRepository.deleteById(id);
    }
}