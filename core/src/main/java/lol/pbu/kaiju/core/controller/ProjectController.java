package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Project;
import lol.pbu.kaiju.core.model.ProjectSearchCard;
import lol.pbu.kaiju.core.repository.ProjectRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

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

    /**
     * Searches active projects by their closest location coordinates within a given radius.
     * Each project is returned only once, representing its closest location within range.
     *
     * @param longitude    the longitude of the center point
     * @param latitude     the latitude of the center point
     * @param radiusMeters the search radius in meters
     * @param pageable     pagination information
     * @return a page of project search cards sorted by distance
     */
    @Get("/search-by-location")
    public Page<ProjectSearchCard> searchByLocation(
            @QueryValue double longitude,
            @QueryValue double latitude,
            @QueryValue double radiusMeters,
            @Valid Pageable pageable
    ) {
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        Point point = geometryFactory.createPoint(new Coordinate(longitude, latitude));
        return projectRepository.searchByLocation(point, radiusMeters, pageable);
    }
}