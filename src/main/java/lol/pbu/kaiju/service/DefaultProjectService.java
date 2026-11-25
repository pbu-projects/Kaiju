package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import lol.pbu.kaiju.domain.Boundary;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.AuditAction;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.repository.AdministrativeRegionRepository;
import lol.pbu.kaiju.repository.OrganizationRepository;
import lol.pbu.kaiju.repository.ProjectRepository;
import lol.pbu.kaiju.security.AuthorizationService;
import lol.pbu.kaiju.security.Permission;
import org.jspecify.annotations.NonNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class DefaultProjectService implements ProjectService {

    private static final String PROJECT_NOT_FOUND = "Project not found";
    private static final String MANAGING_REGION_NOT_EXIST = "Managing region does not exist";
    private static final String LOCATIONS_NOT_IN_REGION = "Project locations do not fall within the specified managing region";
    private static final String UNAUTHORIZED_ASSIGN_REGION = "You do not have authority to assign this managing region";
    private static final String UNAUTHORIZED_UNASSIGN_REGION = "You do not have authority to unassign this managing region";
    private static final String ORGANIZATION_REQUIRED = "Organization is required";

    private final ProjectRepository projectRepository;
    private final AdministrativeRegionRepository administrativeRegionRepository;
    private final OrganizationRepository organizationRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public DefaultProjectService(
            ProjectRepository projectRepository,
            AdministrativeRegionRepository administrativeRegionRepository,
            OrganizationRepository organizationRepository,
            AuthorizationService authorizationService,
            AuditService auditService
    ) {
        this.projectRepository = projectRepository;
        this.administrativeRegionRepository = administrativeRegionRepository;
        this.organizationRepository = organizationRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public CursoredPage<Project> getProjects(@NonNull CursoredPageable pageable) {
        return projectRepository.findByDeletedAtIsNull(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public Optional<Project> getProjectById(@NonNull UUID id) {
        return projectRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    @Transactional
    @NonNull
    public Project createProject(@NonNull Project project, @NonNull UUID actorUserId) {
        User actor = authorizationService.requireActor(actorUserId);
        authorizationService.authorizeProjectCreation(actor, project);

        if (project.organization() == null || project.organization().id() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, ORGANIZATION_REQUIRED);
        }

        List<Location> domainLocations = project.locations() != null ? project.locations() : List.of();
        List<Boundary> domainBoundaries = project.boundaries() != null ? project.boundaries() : List.of();
        ProjectStatus requestedStatus = project.status() != null ? project.status() : ProjectStatus.DRAFT;

        Organization org = organizationRepository.findById(project.organization().id()).orElse(null);
        if (org == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, ORGANIZATION_REQUIRED);
        }

        AdministrativeRegion managingRegion = validateAndResolveManagingRegion(project, org.id(), actor);

        Project candidate = project.withCreationDetails(
                org,
                managingRegion,
                requestedStatus,
                OffsetDateTime.now(ZoneOffset.UTC),
                domainLocations,
                domainBoundaries
        );

        ProjectStatus initialStatus = authorizationService.evaluateProjectCreationByUser(actor, candidate);
        Project saved = projectRepository.save(candidate.withStatus(initialStatus));
        auditService.recordProjectAudit(saved, actor, AuditAction.CREATED);
        return saved;
    }

    private AdministrativeRegion validateAndResolveManagingRegion(
            Project project, UUID orgId, User actor) {
        if (project.managingRegion() == null) {
            return null;
        }
        UUID regionId = project.managingRegion().id();
        if (regionId == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, MANAGING_REGION_NOT_EXIST);
        }
        if (!authorizationService.canAssignManagingRegion(actor, orgId, regionId)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, UNAUTHORIZED_ASSIGN_REGION);
        }
        AdministrativeRegion region = administrativeRegionRepository.findById(regionId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.BAD_REQUEST, MANAGING_REGION_NOT_EXIST));
        if (project.locations() != null && !project.locations().isEmpty()
                && !authorizationService.areAllLocationsInRegion(project, regionId)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, LOCATIONS_NOT_IN_REGION);
        }
        return region;
    }

    @Override
    @Transactional
    @NonNull
    public Project updateProject(@NonNull UUID id, @NonNull Project project, @NonNull UUID actorUserId) {
        User actor = authorizationService.requireActor(actorUserId);
        Project existing = requireModifiableProject(id, actor, "You do not have permission to modify this project");

        Organization targetOrg = resolveTargetOrganization(actor, existing, project);
        UUID existingOrgId = existing.organization() != null ? existing.organization().id() : null;
        UUID effectiveOrgId = targetOrg != null ? targetOrg.id() : existingOrgId;
        AdministrativeRegion targetRegion = resolveTargetRegion(actor, existing, effectiveOrgId, project);

        List<Location> existingLocations = existing.locations() != null ? existing.locations() : List.of();
        List<Boundary> existingBoundaries = existing.boundaries() != null ? existing.boundaries() : List.of();
        List<Location> domainLocations = project.locations() != null ? project.locations() : existingLocations;
        List<Boundary> domainBoundaries = project.boundaries() != null ? project.boundaries() : existingBoundaries;

        Project candidate = existing.withUpdatedDetails(
                targetOrg,
                targetRegion,
                project,
                project.status() != null ? project.status() : existing.status(),
                domainLocations,
                domainBoundaries
        );

        if (targetRegion != null && !domainLocations.isEmpty()
                && !authorizationService.areAllLocationsInRegion(candidate, targetRegion.id())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, LOCATIONS_NOT_IN_REGION);
        }

        ProjectStatus newStatus = determineUpdatedStatus(
                actor,
                existing,
                candidate,
                project.status()
        );

        Project updated = projectRepository.update(candidate.withStatus(newStatus));

        auditService.recordProjectAudit(updated, actor, AuditAction.EDITED);
        return updated;
    }

    private Organization resolveTargetOrganization(User actor, Project existing, Project project) {
        Organization targetOrg = existing.organization();
        UUID existingOrgId = existing.organization() != null ? existing.organization().id() : null;

        if (project.organization() != null) {
            UUID requestedOrgId = project.organization().id();
            if (!Objects.equals(requestedOrgId, existingOrgId)) {
                if (!authorizationService.canReassignProject(actor, existing)) {
                    throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have permission to reassign this project to another organization");
                }
                if (requestedOrgId == null) {
                    throw new HttpStatusException(HttpStatus.NOT_FOUND, "Target organization not found");
                }
                targetOrg = organizationRepository.findById(requestedOrgId)
                        .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Target organization not found"));
            }
        }
        return targetOrg;
    }

    private AdministrativeRegion resolveTargetRegion(
            User actor, Project existing, UUID effectiveOrgId, Project project) {
        UUID existingRegionId = existing.managingRegion() != null ? existing.managingRegion().id() : null;
        UUID requestedRegionId = project.managingRegion() != null ? project.managingRegion().id() : null;

        if (Objects.equals(requestedRegionId, existingRegionId)) {
            return existing.managingRegion();
        }
        if (requestedRegionId != null) {
            return assignManagingRegion(actor, effectiveOrgId, requestedRegionId);
        }
        unassignManagingRegion(actor, effectiveOrgId, existingRegionId);
        return null;
    }

    private AdministrativeRegion assignManagingRegion(
            User actor, UUID effectiveOrgId, UUID requestedRegionId) {
        if (effectiveOrgId == null || !authorizationService.canAssignManagingRegion(actor, effectiveOrgId, requestedRegionId)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, UNAUTHORIZED_ASSIGN_REGION);
        }
        return administrativeRegionRepository.findById(requestedRegionId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.BAD_REQUEST, MANAGING_REGION_NOT_EXIST));
    }

    private void unassignManagingRegion(User actor, UUID effectiveOrgId, UUID existingRegionId) {
        if (effectiveOrgId == null || !authorizationService.canAssignManagingRegion(actor, effectiveOrgId, existingRegionId)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, UNAUTHORIZED_UNASSIGN_REGION);
        }
    }

    private ProjectStatus determineUpdatedStatus(
            User actor,
            Project existing,
            Project transientProject,
            ProjectStatus requestedStatus) {
        if (existing.status() == ProjectStatus.DRAFT
                && requestedStatus != null
                && requestedStatus != ProjectStatus.DRAFT) {
            if (requestedStatus != ProjectStatus.PENDING && requestedStatus != ProjectStatus.ACTIVE) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Draft projects can only be submitted for approval");
            }
            return authorizationService.evaluateProjectCreationByUser(actor, transientProject);
        }

        List<Location> existingLocations = existing.locations() != null ? existing.locations() : List.of();
        UUID existingOrgId = existing.organization() != null ? existing.organization().id() : null;
        Organization targetOrg = transientProject.organization();
        boolean locationsModified = !Objects.equals(transientProject.locations(), existingLocations);
        boolean reassigned = !Objects.equals(targetOrg != null ? targetOrg.id() : null, existingOrgId);
        if ((locationsModified || reassigned)
                && existing.status() == ProjectStatus.ACTIVE
                && (targetOrg == null || !authorizationService.areAllLocationsInOrgRegion(transientProject, targetOrg.id()))) {
            return ProjectStatus.PENDING;
        }
        return existing.status();
    }

    @Override
    @Transactional
    public void deleteProject(@NonNull UUID id, @NonNull UUID actorUserId) {
        User actor = authorizationService.requireActor(actorUserId);
        Project existing = requireModifiableProject(id, actor, "You do not have permission to delete this project");

        Project deleted = projectRepository.update(
                existing.withDeleted(OffsetDateTime.now(ZoneOffset.UTC), actor)
        );

        auditService.recordProjectAudit(deleted, actor, AuditAction.EDITED);
    }

    private Project requireModifiableProject(UUID id, User actor, String forbiddenMessage) {
        Project existing = projectRepository.findById(id).orElse(null);
        if (existing == null) {
            if (actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
                throw new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND);
            }
            throw new HttpStatusException(HttpStatus.FORBIDDEN, forbiddenMessage);
        }
        if (!authorizationService.canModifyProject(actor, existing)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, forbiddenMessage);
        }
        if (existing.deletedAt() != null) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND);
        }
        return existing;
    }

    @Override
    @Transactional
    @NonNull
    public Project approveProject(@NonNull UUID id, @NonNull UUID actorUserId) {
        User actor = authorizationService.authorizeRegionalAdminApproval(actorUserId, id);

        Project existing = projectRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND));

        if (existing.status() != ProjectStatus.PENDING && existing.status() != ProjectStatus.PENDING_UPDATE) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Only PENDING or PENDING_UPDATE projects can be approved");
        }

        Project approved = projectRepository.update(existing.withStatus(ProjectStatus.ACTIVE));

        auditService.recordProjectAudit(approved, actor, AuditAction.APPROVED);
        return approved;
    }

    @Override
    @Transactional
    @NonNull
    public Project rejectProject(@NonNull UUID id, @NonNull UUID actorUserId) {
        User actor = authorizationService.authorizeRegionalAdminApproval(actorUserId, id);

        Project existing = projectRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND));

        if (existing.status() == ProjectStatus.DRAFT || existing.status() == ProjectStatus.REJECTED) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Cannot reject a project in " + existing.status() + " status");
        }

        Project rejected = projectRepository.update(existing.withStatus(ProjectStatus.REJECTED));

        auditService.recordProjectAudit(rejected, actor, AuditAction.REJECTED);
        return rejected;
    }
}
