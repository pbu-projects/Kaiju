package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import lol.pbu.kaiju.core.domain.Project;
import lol.pbu.kaiju.core.repository.ProjectRepository;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.PathVariable;

import java.util.Optional;
import java.util.UUID;
import jakarta.validation.Valid;

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
        var projectWithId = new Project(
            id,
            project.title(),
            project.description(),
            project.status(),
            project.locations()
        );
        return projectRepository.update(projectWithId);
    }

    @Delete("/{id}")
    public void deleteProject(@PathVariable UUID id) {
        projectRepository.deleteById(id);
    }
}