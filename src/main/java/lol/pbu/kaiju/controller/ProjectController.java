package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import lol.pbu.kaiju.domain.Boundary;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.dto.CreateProjectCommand;
import lol.pbu.kaiju.dto.ProjectBoundaryCommand;
import lol.pbu.kaiju.dto.ProjectLocationCommand;
import lol.pbu.kaiju.dto.UpdateProjectCommand;
import lol.pbu.kaiju.model.ProjectSearchCard;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.repository.ProjectRepository;
import lol.pbu.kaiju.security.ProjectSecurityService;
import lol.pbu.kaiju.service.ProjectService;
import lol.pbu.kaiju.util.PageableUtils;
import lol.pbu.kaiju.util.SpatialMappingService;
import org.locationtech.jts.geom.Point;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_ANONYMOUS;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.security.Permission.PROJECT_APPROVE_CLAIM;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/projects")
public class ProjectController {

    public static final String DEFAULT_SORT_FIELD = "title";

    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final SpatialMappingService spatialMappingService;
    private final ProjectSecurityService projectSecurityService;

    public ProjectController(
            ProjectService projectService,
            ProjectRepository projectRepository,
            SpatialMappingService spatialMappingService,
            ProjectSecurityService projectSecurityService
    ) {
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.spatialMappingService = spatialMappingService;
        this.projectSecurityService = projectSecurityService;
    }

    public CursoredPage<Project> getProjects(@Nullable String title, @Nullable @Valid CursoredPageable pageable) {
        return getProjects(title, pageable, null);
    }

    @Secured(IS_ANONYMOUS)
    @Get
    public CursoredPage<Project> getProjects(
            @Nullable String title,
            @Nullable @Valid CursoredPageable pageable,
            @Nullable Principal principal
    ) {
        CursoredPageable resolved = PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD);
        boolean hasTitle = title != null && !title.isBlank();
        UUID userId = principal != null ? UUID.fromString(principal.getName()) : null;
        if (projectSecurityService.isSystemAdmin(userId)) {
            return hasTitle
                    ? projectRepository.findByTitleAndDeletedAtIsNull(title, resolved)
                    : projectRepository.findByDeletedAtIsNull(resolved);
        }
        return hasTitle
                ? projectRepository.findByTitleAndStatusAndDeletedAtIsNull(title, ProjectStatus.ACTIVE, resolved)
                : projectRepository.findByStatusAndDeletedAtIsNull(ProjectStatus.ACTIVE, resolved);
    }

    @Secured(IS_ANONYMOUS)
    @Get("/{id}")
    public Optional<Project> getProject(@PathVariable UUID id, @Nullable Principal principal) {
        Optional<Project> projectOpt = projectRepository.findByIdAndDeletedAtIsNull(id);
        if (projectOpt.isEmpty()) {
            return Optional.empty();
        }
        Project project = projectOpt.get();
        if (project.status() == ProjectStatus.ACTIVE) {
            return projectOpt;
        }
        if (principal == null) {
            return Optional.empty();
        }
        UUID userId = UUID.fromString(principal.getName());
        if (!projectSecurityService.canViewProject(userId, project)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have permission to view this project");
        }
        return projectOpt;
    }

    @Post
    public Project submitProject(@Valid @Body CreateProjectCommand command, Principal principal) {
        Organization org = command.organizationId() != null
                ? new Organization(command.organizationId(), null, null, null, true, null, null, null)
                : null;
        AdministrativeRegion managingRegion = command.managingRegionId() != null
                ? new AdministrativeRegion(command.managingRegionId(), null, null, null)
                : null;

        List<Location> domainLocations = mapLocations(command.locations());
        List<Boundary> domainBoundaries = mapBoundaries(command.boundaries());

        Project transientProject = new Project(
                null,
                org,
                managingRegion,
                command.title(),
                command.description(),
                command.projectType(),
                command.status(),
                null,
                null,
                null,
                domainLocations,
                domainBoundaries
        );

        UUID submitterId = UUID.fromString(principal.getName());
        return projectService.createProject(transientProject, submitterId);
    }

    /**
     * Updates an existing project by its ID after validating that it exists.
     */
    @Put("/{id}")
    public Project updateProject(@PathVariable UUID id, @Valid @Body UpdateProjectCommand command, Principal principal) {
        Organization org = command.organizationId() != null
                ? new Organization(command.organizationId(), null, null, null, true, null, null, null)
                : null;
        AdministrativeRegion managingRegion = command.managingRegionId() != null
                ? new AdministrativeRegion(command.managingRegionId(), null, null, null)
                : null;

        List<Location> domainLocations = mapLocations(command.locations());
        List<Boundary> domainBoundaries = mapBoundaries(command.boundaries());

        Project transientProject = new Project(
                id,
                org,
                managingRegion,
                command.title(),
                command.description(),
                command.projectType(),
                null,
                null,
                null,
                null,
                domainLocations,
                domainBoundaries
        );

        UUID userId = UUID.fromString(principal.getName());
        return projectService.updateProject(id, transientProject, userId);
    }

    /**
     * Deletes a project by its ID after validating that it exists.
     */
    @Delete("/{id}")
    public void deleteProject(@PathVariable UUID id, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        projectService.deleteProject(id, userId);
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
    @Secured(IS_ANONYMOUS)
    @Get("/search-by-location")
    public Page<ProjectSearchCard> searchByLocation(
            @QueryValue @Min(-180) @Max(180) double longitude,
            @QueryValue @Min(-90) @Max(90) double latitude,
            @QueryValue @Positive @Max(500000) double radiusMeters,
            @Valid Pageable pageable
    ) {
        Point point = spatialMappingService.toPoint(longitude, latitude);
        return projectRepository.searchByLocation(point, radiusMeters, pageable);
    }

    /**
     * Endpoint for Regional Admins to approve a pending project.
     * The service layer enforces that the Regional Admin actually has geographic jurisdiction.
     */
    @Put("/{id}/status")
    @Secured(PROJECT_APPROVE_CLAIM)
    @NonNull
    public Project approveProject(@PathVariable @NonNull UUID id, @NonNull Principal principal) {
        UUID regionalAdminId = UUID.fromString(principal.getName());
        return projectService.approveProject(id, regionalAdminId);
    }

    private List<Location> mapLocations(List<ProjectLocationCommand> locations) {
        if (locations == null) {
            return List.of();
        }
        return locations.stream()
                .map(loc -> new Location(
                        null,
                        loc.name(),
                        loc.addressLine(),
                        loc.city(),
                        loc.stateProvince(),
                        loc.postalCode(),
                        loc.countryCode(),
                        spatialMappingService.toPoint(loc.longitude(), loc.latitude())
                ))
                .toList();
    }

    private List<Boundary> mapBoundaries(List<ProjectBoundaryCommand> boundaries) {
        if (boundaries == null) {
            return List.of();
        }
        return boundaries.stream()
                .map(bnd -> new Boundary(
                        null,
                        bnd.name(),
                        spatialMappingService.toPolygon(bnd.coordinates())
                ))
                .toList();
    }
}