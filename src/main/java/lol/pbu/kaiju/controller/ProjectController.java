package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
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
import io.micronaut.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.ProjectAuditLog;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.AuditAction;
import lol.pbu.kaiju.model.ProjectSearchCard;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.repository.OrganizationRepository;
import lol.pbu.kaiju.repository.ProjectAuditLogRepository;
import lol.pbu.kaiju.repository.ProjectRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.ProjectSecurityService;
import org.jspecify.annotations.NonNull;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.BAD_REQUEST;
import static io.micronaut.http.HttpStatus.FORBIDDEN;
import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.model.ProjectStatus.ACTIVE;
import static lol.pbu.kaiju.model.ProjectStatus.PENDING;
import static lol.pbu.kaiju.security.Permission.PROJECT_APPROVE_CLAIM;


@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/projects")
public class ProjectController {
    private static final String PROJECT_NOT_FOUND = "Project not found";

    private final ProjectRepository projectRepository;
    private final ProjectSecurityService securityService;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final ProjectAuditLogRepository projectAuditLogRepository;

    public ProjectController(
            ProjectRepository projectRepository,
            ProjectSecurityService securityService,
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            ProjectAuditLogRepository projectAuditLogRepository
    ) {
        this.projectRepository = projectRepository;
        this.securityService = securityService;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.projectAuditLogRepository = projectAuditLogRepository;
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
    @Secured(IS_AUTHENTICATED)
    public Project submitProject(@Valid @Body Project project, Principal principal) {
        if (project.organization() == null) {
            throw new HttpStatusException(BAD_REQUEST, "Organization is required");
        }
        
        UUID submitterId = UUID.fromString(principal.getName());
        
        // Evaluate the entire project's locations securely
        ProjectStatus evaluatedStatus = securityService.evaluateProjectCreationByUser(submitterId, project);

        Project secureProject = new Project(
                null, // Force auto-generation
                project.organization(),
                project.managingRegion(),
                project.title(),
                project.description(),
                project.projectType(),
                evaluatedStatus,
                java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault()),
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
    @Secured(IS_AUTHENTICATED)
    public Project updateProject(@PathVariable UUID id, @Valid @Body Project project, Principal principal) {
        Project existing = projectRepository.findById(id).orElseThrow(() -> new HttpStatusException(NOT_FOUND, PROJECT_NOT_FOUND));
        
        UUID userId = UUID.fromString(principal.getName());
        if (!securityService.canModifyProject(userId, existing)) {
            throw new HttpStatusException(FORBIDDEN, "You do not have permission to modify this project");
        }

        Organization targetOrg = existing.organization();
        UUID existingOrgId = existing.organization() != null ? existing.organization().id() : null;

        if (project.organization() != null) {
            UUID requestedOrgId = project.organization().id();
            if (!Objects.equals(requestedOrgId, existingOrgId)) {
                if (!securityService.canReassignProject(userId, existing)) {
                    throw new HttpStatusException(FORBIDDEN, "You do not have permission to reassign this project to another organization");
                }
                if (requestedOrgId == null) {
                    throw new HttpStatusException(NOT_FOUND, "Target organization not found");
                }
                targetOrg = organizationRepository.findById(requestedOrgId)
                        .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Target organization not found"));
            }
        }

        ProjectStatus newStatus = existing.status();
        boolean locationsModified = !Objects.equals(project.locations(), existing.locations());
        boolean reassigned = !Objects.equals(targetOrg != null ? targetOrg.id() : null, existingOrgId);

        if (locationsModified || reassigned) {
            if (existing.status() == ACTIVE && (targetOrg == null || !securityService.areAllLocationsInOrgRegion(project, targetOrg.id()))) {
                newStatus = PENDING;
            }
        }
        
        // Prevent users from unilaterally modifying the status during an update and fix mass assignment
        Project secureProject = new Project(
                id,
                targetOrg,
                project.managingRegion(),
                project.title(),
                project.description(),
                project.projectType(),
                newStatus, 
                existing.createdAt(),
                existing.deletedAt(),
                existing.deletedBy(),
                project.locations(),
                project.boundaries()
        );
        return projectRepository.update(secureProject);
    }


    /**
     * Deletes a project by its ID after validating that it exists.
     */
    @Delete("/{id}")
    @Secured(IS_AUTHENTICATED)
    public void deleteProject(@PathVariable UUID id, Principal principal) {
        Project existing = projectRepository.findById(id).orElseThrow(() -> new HttpStatusException(NOT_FOUND, PROJECT_NOT_FOUND));
        
        UUID userId = UUID.fromString(principal.getName());
        if (!securityService.canModifyProject(userId, existing)) {
            throw new HttpStatusException(FORBIDDEN, "You do not have permission to delete this project");
        }
        
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

    /**
     * Endpoint for Regional Admins to approve a pending project.
     * The service layer enforces that the Regional Admin actually has geographic jurisdiction.
     */
    @Put("/{id}/status")
    @Secured(PROJECT_APPROVE_CLAIM)
    @Transactional
    @NonNull
    public Project approveProject(@PathVariable @NonNull UUID id, @NonNull Principal principal) {
        UUID regionalAdminId = UUID.fromString(principal.getName());

        // Fetch the project and validate its current state first
        Project project = projectRepository.findById(id).orElseThrow(() -> new HttpStatusException(NOT_FOUND, PROJECT_NOT_FOUND));

        if (project.deletedAt() != null) {
            throw new HttpStatusException(NOT_FOUND, PROJECT_NOT_FOUND);
        }

        if (project.status() != PENDING && project.status() != ProjectStatus.PENDING_UPDATE) {
            throw new HttpStatusException(BAD_REQUEST, "Only PENDING or PENDING_UPDATE projects can be approved");
        }

        // Ensure they have geographic jurisdiction to approve it
        securityService.authorizeRegionalAdminApproval(regionalAdminId, id);

        Project approvedProject = projectRepository.update(new Project(
                project.id(),
                project.organization(),
                project.managingRegion(),
                project.title(),
                project.description(),
                project.projectType(),
                ACTIVE,
                project.createdAt(),
                project.deletedAt(),
                project.deletedBy(),
                project.locations(),
                project.boundaries()
        ));

        User actor = userRepository.findById(regionalAdminId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "User not found"));
        projectAuditLogRepository.save(new ProjectAuditLog(
                null,
                approvedProject,
                actor,
                AuditAction.APPROVED,
                OffsetDateTime.now()
        ));

        return approvedProject;
    }
}