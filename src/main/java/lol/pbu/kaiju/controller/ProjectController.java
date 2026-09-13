package lol.pbu.kaiju.controller;

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
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.model.ProjectSearchCard;
import lol.pbu.kaiju.repository.ProjectRepository;
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
    @io.micronaut.security.annotation.Secured("isAuthenticated()")
    public Project addProject(@Valid @Body Project project, java.security.Principal principal, lol.pbu.kaiju.security.ProjectSecurityService securityService) {
        if (project.organization() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Organization is required");
        }
        
        UUID userId = UUID.fromString(principal.getName());
        
        // Evaluate the entire project's locations securely
        lol.pbu.kaiju.model.ProjectStatus evaluatedStatus = securityService.evaluateProjectCreation(userId, project);

        // Fix ID hijacking (force null ID for creation), fix mass assignment (force tracking fields)
        Project secureProject = new Project(
                null, // Force auto-generation
                project.organization(),
                project.managingRegion(),
                project.title(),
                project.description(),
                project.projectType(),
                evaluatedStatus,
                java.time.OffsetDateTime.now(),
                null,
                null,
                project.locations(),
                project.boundaries()
        );
        
        return projectRepository.save(secureProject);
    }

    /**
     * Updates an existing project by its ID after validating that it exists.
     */
    @Put("/{id}")
    @io.micronaut.security.annotation.Secured("isAuthenticated()")
    public Project updateProject(@PathVariable UUID id, @Valid @Body Project project, java.security.Principal principal, lol.pbu.kaiju.security.ProjectSecurityService securityService) {
        Project existing = projectRepository.findById(id).orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        
        UUID userId = UUID.fromString(principal.getName());
        if (!securityService.canModifyProject(userId, existing)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have permission to modify this project");
        }
        
        // Prevent users from unilaterally modifying the status during an update and fix mass assignment
        Project secureProject = new Project(
                id,
                project.organization() != null ? project.organization() : existing.organization(),
                project.managingRegion(),
                project.title(),
                project.description(),
                project.projectType(),
                existing.status(), 
                existing.createdAt(),
                existing.deletedAt(),
                existing.deletedBy(),
                project.locations(),
                project.boundaries()
        );
        return projectRepository.update(secureProject);
    }

    /**
     * Updates a project by its ID without checking if it exists first.
     */
    @Put("/{id}/no-look")
    @io.micronaut.security.annotation.Secured("isAuthenticated()")
    public Project updateProjectNoLook(@PathVariable UUID id, @Valid @Body Project project) {
        // Disabled for security, redirect to safe method
        throw new HttpStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Use /projects/{id} instead");
    }

    /**
     * Deletes a project by its ID after validating that it exists.
     */
    @Delete("/{id}")
    @io.micronaut.security.annotation.Secured("isAuthenticated()")
    public void deleteProject(@PathVariable UUID id, java.security.Principal principal, lol.pbu.kaiju.security.ProjectSecurityService securityService) {
        Project existing = projectRepository.findById(id).orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        
        UUID userId = UUID.fromString(principal.getName());
        if (!securityService.canModifyProject(userId, existing)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have permission to delete this project");
        }
        
        projectRepository.deleteById(id);
    }

    /**
     * Deletes a project by its ID without checking if it exists first.
     */
    @Delete("/{id}/no-look")
    @io.micronaut.security.annotation.Secured("isAuthenticated()")
    public void deleteProjectNoLook(@PathVariable UUID id) {
        // Disabled for security
        throw new HttpStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Use /projects/{id} instead");
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

    /**
     * Endpoint for Regional Admins to approve a pending project.
     * The service layer enforces that the Regional Admin actually has geographic jurisdiction.
     */
    @Put("/{id}/status")
    @io.micronaut.security.annotation.Secured({"REGION_AGENT", "REGION_DIRECTOR"})
    public Project approveProject(@PathVariable UUID id, java.security.Principal principal, lol.pbu.kaiju.security.ProjectSecurityService securityService) {
        UUID regionalAdminId = UUID.fromString(principal.getName());
        
        // Ensure they have geographic jurisdiction to approve it
        securityService.authorizeRegionalAdminApproval(regionalAdminId, id);

        // Fetch the project and validate its current state
        Project project = projectRepository.findById(id).orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        
        if (project.status() != lol.pbu.kaiju.model.ProjectStatus.PENDING) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Only PENDING projects can be approved");
        }
        
        return projectRepository.update(new Project(
                project.id(),
                project.organization(),
                project.managingRegion(),
                project.title(),
                project.description(),
                project.projectType(),
                lol.pbu.kaiju.model.ProjectStatus.ACTIVE,
                project.createdAt(),
                project.deletedAt(),
                project.deletedBy(),
                project.locations(),
                project.boundaries()
        ));
    }
}