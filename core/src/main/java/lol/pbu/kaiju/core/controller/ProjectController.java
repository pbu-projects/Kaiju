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

    @Put("/{id}")
    public Project updateProject(@PathVariable UUID id, @Valid @Body Project project) {
        if (!projectRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return projectRepository.update(project.withId(id));
    }

    @Delete("/{id}")
    public void deleteProject(@PathVariable UUID id) {
        projectRepository.deleteById(id);
    }
}