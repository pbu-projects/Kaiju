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
import lol.pbu.kaiju.dto.CreateProjectRequest;
import lol.pbu.kaiju.dto.ProjectBoundaryRequest;
import lol.pbu.kaiju.dto.ProjectLocationRequest;
import lol.pbu.kaiju.dto.UpdateProjectRequest;
import lol.pbu.kaiju.model.ProjectSearchCard;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.repository.ProjectRepository;
import lol.pbu.kaiju.security.AuthorizationService;
import lol.pbu.kaiju.service.ProjectService;
import lol.pbu.kaiju.service.SpatialMappingService;
import lol.pbu.kaiju.util.PageRequests;
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
    private final AuthorizationService authorizationService;

    public ProjectController(
            ProjectService projectService,
            ProjectRepository projectRepository,
            SpatialMappingService spatialMappingService,
            AuthorizationService authorizationService
    ) {
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.spatialMappingService = spatialMappingService;
        this.authorizationService = authorizationService;
    }

    @Secured(IS_ANONYMOUS)
    @Get
    public CursoredPage<Project> getProjects(
            @Nullable String title,
            @Nullable @Valid CursoredPageable pageable,
            @Nullable Principal principal
    ) {
        CursoredPageable resolved = PageRequests.resolvePageable(pageable, DEFAULT_SORT_FIELD);
        boolean hasTitle = title != null && !title.isBlank();
        UUID userId = principal != null ? UUID.fromString(principal.getName()) : null;
        if (authorizationService.isSystemAdmin(userId)) {
            return hasTitle
                    ? projectRepository.findByTitleAndDeletedAtIsNull(title, resolved)
                    : projectService.getProjects(resolved);
        }
        return hasTitle
                ? projectRepository.findByTitleAndStatusAndDeletedAtIsNull(title, ProjectStatus.ACTIVE, resolved)
                : projectRepository.findByStatusAndDeletedAtIsNull(ProjectStatus.ACTIVE, resolved);
    }

    @Secured(IS_ANONYMOUS)
    @Get("/{id}")
    public Optional<Project> getProject(@PathVariable UUID id, @Nullable Principal principal) {
        Optional<Project> projectOpt = projectService.getProjectById(id);
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
        if (!authorizationService.canViewProject(userId, project)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have permission to view this project");
        }
        return projectOpt;
    }

    @Post
    public Project addProject(@Valid @Body CreateProjectRequest request, Principal principal) {
        Organization org = request.organizationId() != null
                ? Organization.forId(request.organizationId())
                : null;
        AdministrativeRegion managingRegion = request.managingRegionId() != null
                ? AdministrativeRegion.forId(request.managingRegionId())
                : null;

        List<Location> domainLocations = mapLocations(request.locations());
        List<Boundary> domainBoundaries = mapBoundaries(request.boundaries());

        Project transientProject = Project.forCreation(
                org,
                managingRegion,
                request.title(),
                request.description(),
                request.projectType(),
                request.status(),
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
    public Project updateProject(@PathVariable UUID id, @Valid @Body UpdateProjectRequest request, Principal principal) {
        Organization org = request.organizationId() != null
                ? Organization.forId(request.organizationId())
                : null;
        AdministrativeRegion managingRegion = request.managingRegionId() != null
                ? AdministrativeRegion.forId(request.managingRegionId())
                : null;

        List<Location> domainLocations = mapLocations(request.locations());
        List<Boundary> domainBoundaries = mapBoundaries(request.boundaries());

        Project transientProject = Project.forUpdate(
                id,
                org,
                managingRegion,
                request.title(),
                request.description(),
                request.projectType(),
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

    @Nullable
    private List<Location> mapLocations(@Nullable List<ProjectLocationRequest> locations) {
        if (locations == null) {
            return null;
        }
        return locations.stream()
                .map(loc -> Location.forCreation(
                        loc.name(),
                        loc.addressLine(),
                        loc.city(),
                        loc.stateProvince(),
                        loc.postalCode(),
                        loc.countryCode(),
                        spatialMappingService.toPoint(loc.longitude(), loc.latitude()),
                        loc.timezone()
                ))
                .toList();
    }

    @Nullable
    private List<Boundary> mapBoundaries(@Nullable List<ProjectBoundaryRequest> boundaries) {
        if (boundaries == null) {
            return null;
        }
        return boundaries.stream()
                .map(bnd -> Boundary.forCreation(
                        bnd.name(),
                        spatialMappingService.toPolygon(bnd.coordinates())
                ))
                .toList();
    }
}
