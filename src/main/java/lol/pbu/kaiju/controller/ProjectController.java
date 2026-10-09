package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import lol.pbu.kaiju.domain.Boundary;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.ProjectAuditLog;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.dto.CreateProjectCommand;
import lol.pbu.kaiju.dto.ProjectBoundaryCommand;
import lol.pbu.kaiju.dto.ProjectLocationCommand;
import lol.pbu.kaiju.dto.UpdateProjectCommand;
import lol.pbu.kaiju.model.AuditAction;
import lol.pbu.kaiju.model.ProjectSearchCard;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.repository.AdministrativeRegionRepository;
import lol.pbu.kaiju.repository.OrganizationRepository;
import lol.pbu.kaiju.repository.ProjectAuditLogRepository;
import lol.pbu.kaiju.repository.ProjectRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.ProjectSecurityService;
import lol.pbu.kaiju.util.ControllerUtils;
import lol.pbu.kaiju.util.SpatialMappingService;
import org.locationtech.jts.geom.Point;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
@Secured(IS_AUTHENTICATED)
@Controller("/projects")
public class ProjectController implements ControllerUtils {

    public static final String DEFAULT_SORT_FIELD = "title";
    private static final String PROJECT_NOT_FOUND = "Project not found";
    private static final String MANAGING_REGION_NOT_EXIST = "Managing region does not exist";
    private static final String LOCATIONS_NOT_IN_REGION = "Project locations do not fall within the specified managing region";
    private static final String UNAUTHORIZED_ASSIGN_REGION = "You do not have authority to assign this managing region";
    private static final String UNAUTHORIZED_UNASSIGN_REGION = "You do not have authority to unassign this managing region";
    private static final String ORGANIZATION_REQUIRED = "Organization is required";

    private final ProjectRepository projectRepository;
    private final ProjectSecurityService securityService;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final ProjectAuditLogRepository projectAuditLogRepository;
    private final AdministrativeRegionRepository administrativeRegionRepository;
    private final SpatialMappingService spatialMappingService;

    public ProjectController(
            ProjectRepository projectRepository,
            ProjectSecurityService securityService,
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            ProjectAuditLogRepository projectAuditLogRepository,
            AdministrativeRegionRepository administrativeRegionRepository,
            SpatialMappingService spatialMappingService
    ) {
        this.projectRepository = projectRepository;
        this.securityService = securityService;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.projectAuditLogRepository = projectAuditLogRepository;
        this.administrativeRegionRepository = administrativeRegionRepository;
        this.spatialMappingService = spatialMappingService;
    }

    @Get
    public CursoredPage<Project> getProjects(@Nullable String title, @Nullable @Valid CursoredPageable pageable) {
        CursoredPageable resolved = resolvePageable(pageable, DEFAULT_SORT_FIELD);
        if (title == null || title.isBlank()) {
            return projectRepository.findAll(resolved);
        }
        return projectRepository.findByTitle(title, resolved);
    }

    @Get("/{id}")
    public Optional<Project> getProject(@PathVariable UUID id) {
        return projectRepository.findById(id);
    }

    @Post
    public Project submitProject(@Valid @Body CreateProjectCommand command, Principal principal) {
        if (command.organizationId() == null) {
            throw new HttpStatusException(BAD_REQUEST, ORGANIZATION_REQUIRED);
        }
        Organization org = organizationRepository.findById(command.organizationId())
                .orElseThrow(() -> new HttpStatusException(BAD_REQUEST, ORGANIZATION_REQUIRED));

        UUID submitterId = UUID.fromString(principal.getName());
        AdministrativeRegion managingRegion = null;

        if (command.managingRegionId() != null) {
            managingRegion = administrativeRegionRepository.findById(command.managingRegionId())
                    .orElseThrow(() -> new HttpStatusException(BAD_REQUEST, MANAGING_REGION_NOT_EXIST));
        }

        List<Location> domainLocations = mapLocations(command.locations());
        List<Boundary> domainBoundaries = mapBoundaries(command.boundaries());

        Project transientProject = new Project(
                null,
                org,
                managingRegion,
                command.title(),
                command.description(),
                command.projectType(),
                command.status() != null ? command.status() : ProjectStatus.DRAFT,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                domainLocations,
                domainBoundaries
        );

        if (managingRegion != null) {
            if (!domainLocations.isEmpty()
                    && !securityService.areAllLocationsInRegion(transientProject, managingRegion.id())) {
                throw new HttpStatusException(BAD_REQUEST, LOCATIONS_NOT_IN_REGION);
            }
            if (!securityService.canAssignManagingRegion(submitterId, org.id(), managingRegion.id())) {
                throw new HttpStatusException(FORBIDDEN, UNAUTHORIZED_ASSIGN_REGION);
            }
        }

        ProjectStatus evaluatedStatus = securityService.evaluateProjectCreationByUser(submitterId, transientProject);

        Project secureProject = new Project(
                null,
                org,
                managingRegion,
                command.title(),
                command.description(),
                command.projectType(),
                evaluatedStatus,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                domainLocations,
                domainBoundaries
        );

        return projectRepository.save(secureProject);
    }

    /**
     * Updates an existing project by its ID after validating that it exists.
     */
    @Put("/{id}")
    public Project updateProject(@PathVariable UUID id, @Valid @Body UpdateProjectCommand command, Principal principal) {
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, PROJECT_NOT_FOUND));

        UUID userId = UUID.fromString(principal.getName());
        if (!securityService.canModifyProject(userId, existing)) {
            throw new HttpStatusException(FORBIDDEN, "You do not have permission to modify this project");
        }

        Organization targetOrg = resolveTargetOrganization(userId, existing, command.organizationId());
        UUID existingOrgId = existing.organization() != null ? existing.organization().id() : null;
        UUID effectiveOrgId = targetOrg != null ? targetOrg.id() : existingOrgId;
        AdministrativeRegion targetRegion = resolveTargetRegion(userId, existing, effectiveOrgId, command.managingRegionId());

        List<Location> domainLocations = mapLocations(command.locations());
        List<Boundary> domainBoundaries = mapBoundaries(command.boundaries());

        Project transientProject = new Project(
                id,
                targetOrg,
                targetRegion,
                command.title(),
                command.description(),
                command.projectType(),
                existing.status(),
                existing.createdAt(),
                existing.deletedAt(),
                existing.deletedBy(),
                domainLocations,
                domainBoundaries
        );

        if (targetRegion != null && !domainLocations.isEmpty()
                && !securityService.areAllLocationsInRegion(transientProject, targetRegion.id())) {
            throw new HttpStatusException(BAD_REQUEST, LOCATIONS_NOT_IN_REGION);
        }

        ProjectStatus newStatus = determineUpdatedStatus(existing, transientProject, targetOrg, existingOrgId, domainLocations);

        Project secureProject = new Project(
                id,
                targetOrg,
                targetRegion,
                command.title(),
                command.description(),
                command.projectType(),
                newStatus,
                existing.createdAt(),
                existing.deletedAt(),
                existing.deletedBy(),
                domainLocations,
                domainBoundaries
        );

        return projectRepository.update(secureProject);
    }

    private Organization resolveTargetOrganization(UUID userId, Project existing, UUID requestedOrgId) {
        Organization targetOrg = existing.organization();
        UUID existingOrgId = existing.organization() != null ? existing.organization().id() : null;
        if (requestedOrgId != null && !Objects.equals(requestedOrgId, existingOrgId)) {
            if (!securityService.canReassignProject(userId, existing)) {
                throw new HttpStatusException(FORBIDDEN, "You do not have permission to reassign this project to another organization");
            }
            targetOrg = organizationRepository.findById(requestedOrgId)
                    .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Target organization not found"));
        }
        return targetOrg;
    }

    private AdministrativeRegion resolveTargetRegion(UUID userId, Project existing, UUID effectiveOrgId, UUID requestedRegionId) {
        UUID existingRegionId = existing.managingRegion() != null ? existing.managingRegion().id() : null;
        if (Objects.equals(requestedRegionId, existingRegionId)) {
            return existing.managingRegion();
        }
        if (requestedRegionId != null) {
            AdministrativeRegion targetRegion = administrativeRegionRepository.findById(requestedRegionId)
                    .orElseThrow(() -> new HttpStatusException(BAD_REQUEST, MANAGING_REGION_NOT_EXIST));
            if (effectiveOrgId == null || !securityService.canAssignManagingRegion(userId, effectiveOrgId, requestedRegionId)) {
                throw new HttpStatusException(FORBIDDEN, UNAUTHORIZED_ASSIGN_REGION);
            }
            return targetRegion;
        }
        if (existingRegionId != null && (effectiveOrgId == null || !securityService.canAssignManagingRegion(userId, effectiveOrgId, existingRegionId))) {
            throw new HttpStatusException(FORBIDDEN, UNAUTHORIZED_UNASSIGN_REGION);
        }
        return null;
    }

    private ProjectStatus determineUpdatedStatus(
            Project existing,
            Project transientProject,
            Organization targetOrg,
            UUID existingOrgId,
            List<Location> domainLocations) {
        boolean locationsModified = !Objects.equals(domainLocations, existing.locations());
        boolean reassigned = !Objects.equals(targetOrg != null ? targetOrg.id() : null, existingOrgId);

        if ((locationsModified || reassigned)
                && existing.status() == ACTIVE
                && (targetOrg == null || !securityService.areAllLocationsInOrgRegion(transientProject, targetOrg.id()))) {
            return PENDING;
        }
        return existing.status();
    }

    /**
     * Deletes a project by its ID after validating that it exists.
     */
    @Delete("/{id}")
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
    @Transactional
    @NonNull
    public Project approveProject(@PathVariable @NonNull UUID id, @NonNull Principal principal) {
        UUID regionalAdminId = UUID.fromString(principal.getName());

        Project project = projectRepository.findById(id).orElseThrow(() -> new HttpStatusException(NOT_FOUND, PROJECT_NOT_FOUND));

        if (project.deletedAt() != null) {
            throw new HttpStatusException(NOT_FOUND, PROJECT_NOT_FOUND);
        }

        if (project.status() != PENDING && project.status() != ProjectStatus.PENDING_UPDATE) {
            throw new HttpStatusException(BAD_REQUEST, "Only PENDING or PENDING_UPDATE projects can be approved");
        }

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
                OffsetDateTime.now(ZoneOffset.UTC)
        ));

        return approvedProject;
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