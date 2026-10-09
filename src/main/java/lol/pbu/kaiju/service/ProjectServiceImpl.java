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
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.ProjectSecurityService;
import org.jspecify.annotations.NonNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class ProjectServiceImpl implements ProjectService {

    private static final String PROJECT_NOT_FOUND = "Project not found";
    private static final String MANAGING_REGION_NOT_EXIST = "Managing region does not exist";
    private static final String LOCATIONS_NOT_IN_REGION = "Project locations do not fall within the specified managing region";
    private static final String UNAUTHORIZED_ASSIGN_REGION = "You do not have authority to assign this managing region";
    private static final String UNAUTHORIZED_UNASSIGN_REGION = "You do not have authority to unassign this managing region";
    private static final String ORGANIZATION_REQUIRED = "Organization is required";

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AdministrativeRegionRepository administrativeRegionRepository;
    private final OrganizationRepository organizationRepository;
    private final ProjectSecurityService securityService;
    private final AuditService auditService;

    public ProjectServiceImpl(
            ProjectRepository projectRepository,
            UserRepository userRepository,
            AdministrativeRegionRepository administrativeRegionRepository,
            OrganizationRepository organizationRepository,
            ProjectSecurityService securityService,
            AuditService auditService
    ) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.administrativeRegionRepository = administrativeRegionRepository;
        this.organizationRepository = organizationRepository;
        this.securityService = securityService;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public CursoredPage<Project> getProjects(@NonNull CursoredPageable pageable) {
        return projectRepository.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public Optional<Project> getProjectById(@NonNull UUID id) {
        return projectRepository.findById(id);
    }

    @Override
    @Transactional
    @NonNull
    public Project createProject(@NonNull Project project, @NonNull UUID actorUserId) {
        if (project.organization() == null || project.organization().id() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, ORGANIZATION_REQUIRED);
        }

        Organization org = organizationRepository.findById(project.organization().id())
                .orElseThrow(() -> new HttpStatusException(HttpStatus.BAD_REQUEST, ORGANIZATION_REQUIRED));

        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        AdministrativeRegion managingRegion = null;
        if (project.managingRegion() != null) {
            UUID regionId = project.managingRegion().id();
            if (regionId == null || !administrativeRegionRepository.existsById(regionId)) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, MANAGING_REGION_NOT_EXIST);
            }
            if (project.locations() != null && !project.locations().isEmpty()) {
                if (!securityService.areAllLocationsInRegion(project, regionId)) {
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST, LOCATIONS_NOT_IN_REGION);
                }
            }
            if (!securityService.canAssignManagingRegion(actorUserId, org.id(), regionId)) {
                throw new HttpStatusException(HttpStatus.FORBIDDEN, UNAUTHORIZED_ASSIGN_REGION);
            }
            managingRegion = administrativeRegionRepository.findById(regionId).orElse(project.managingRegion());
        }

        Project transientProject = new Project(
                null,
                org,
                managingRegion,
                project.title(),
                project.description(),
                project.projectType(),
                project.status() != null ? project.status() : ProjectStatus.DRAFT,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                project.locations() != null ? project.locations() : List.of(),
                project.boundaries() != null ? project.boundaries() : List.of()
        );

        ProjectStatus initialStatus = securityService.evaluateProjectCreationByUser(actorUserId, transientProject);

        Project toSave = new Project(
                null,
                org,
                managingRegion,
                project.title(),
                project.description(),
                project.projectType(),
                initialStatus,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                project.locations() != null ? project.locations() : List.of(),
                project.boundaries() != null ? project.boundaries() : List.of()
        );

        Project saved = projectRepository.save(toSave);
        auditService.recordProjectAudit(saved, actor, AuditAction.CREATED);
        return saved;
    }

    @Override
    @Transactional
    @NonNull
    public Project updateProject(@NonNull UUID id, @NonNull Project project, @NonNull UUID actorUserId) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND));

        if (!securityService.canModifyProject(actorUserId, existing)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have permission to modify this project");
        }

        Organization targetOrg = existing.organization();
        UUID existingOrgId = existing.organization() != null ? existing.organization().id() : null;

        if (project.organization() != null) {
            UUID requestedOrgId = project.organization().id();
            if (!Objects.equals(requestedOrgId, existingOrgId)) {
                if (!securityService.canReassignProject(actorUserId, existing)) {
                    throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have permission to reassign this project to another organization");
                }
                if (requestedOrgId == null) {
                    throw new HttpStatusException(HttpStatus.NOT_FOUND, "Target organization not found");
                }
                targetOrg = organizationRepository.findById(requestedOrgId)
                        .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Target organization not found"));
            }
        }

        AdministrativeRegion targetRegion = existing.managingRegion();
        UUID existingRegionId = existing.managingRegion() != null ? existing.managingRegion().id() : null;
        UUID requestedRegionId = project.managingRegion() != null ? project.managingRegion().id() : null;
        UUID effectiveOrgId = targetOrg != null ? targetOrg.id() : existingOrgId;

        if (!Objects.equals(requestedRegionId, existingRegionId)) {
            if (requestedRegionId != null) {
                if (!administrativeRegionRepository.existsById(requestedRegionId)) {
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST, MANAGING_REGION_NOT_EXIST);
                }
                if (effectiveOrgId == null || !securityService.canAssignManagingRegion(actorUserId, effectiveOrgId, requestedRegionId)) {
                    throw new HttpStatusException(HttpStatus.FORBIDDEN, UNAUTHORIZED_ASSIGN_REGION);
                }
                targetRegion = administrativeRegionRepository.findById(requestedRegionId).orElse(project.managingRegion());
            } else {
                if (effectiveOrgId == null || !securityService.canAssignManagingRegion(actorUserId, effectiveOrgId, existingRegionId)) {
                    throw new HttpStatusException(HttpStatus.FORBIDDEN, UNAUTHORIZED_UNASSIGN_REGION);
                }
                targetRegion = null;
            }
        }

        List<Location> domainLocations = project.locations() != null ? project.locations() : existing.locations();
        List<Boundary> domainBoundaries = project.boundaries() != null ? project.boundaries() : existing.boundaries();

        Project transientProject = new Project(
                id,
                targetOrg,
                targetRegion,
                project.title(),
                project.description(),
                project.projectType(),
                existing.status(),
                existing.createdAt(),
                existing.deletedAt(),
                existing.deletedBy(),
                domainLocations,
                domainBoundaries
        );

        if (targetRegion != null && !domainLocations.isEmpty()
                && !securityService.areAllLocationsInRegion(transientProject, targetRegion.id())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, LOCATIONS_NOT_IN_REGION);
        }

        boolean locationsModified = !Objects.equals(domainLocations, existing.locations());
        boolean reassigned = !Objects.equals(targetOrg != null ? targetOrg.id() : null, existingOrgId);
        ProjectStatus newStatus = existing.status();
        if ((locationsModified || reassigned)
                && existing.status() == ProjectStatus.ACTIVE
                && (targetOrg == null || !securityService.areAllLocationsInOrgRegion(transientProject, targetOrg.id()))) {
            newStatus = ProjectStatus.PENDING;
        }

        Project updated = projectRepository.update(new Project(
                id,
                targetOrg,
                targetRegion,
                project.title(),
                project.description(),
                project.projectType(),
                newStatus,
                existing.createdAt(),
                existing.deletedAt(),
                existing.deletedBy(),
                domainLocations,
                domainBoundaries
        ));

        auditService.recordProjectAudit(updated, actor, AuditAction.EDITED);
        return updated;
    }

    @Override
    @Transactional
    public void deleteProject(@NonNull UUID id, @NonNull UUID actorUserId) {
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND));

        if (!securityService.canModifyProject(actorUserId, existing)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have permission to delete this project");
        }

        long count = projectRepository.removeById(id);
        if (count == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    @NonNull
    public Project approveProject(@NonNull UUID id, @NonNull UUID actorUserId) {
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND));

        if (existing.deletedAt() != null) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND);
        }

        if (existing.status() != ProjectStatus.PENDING && existing.status() != ProjectStatus.PENDING_UPDATE) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Only PENDING or PENDING_UPDATE projects can be approved");
        }

        securityService.authorizeRegionalAdminApproval(actorUserId, id);

        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Project approved = projectRepository.update(new Project(
                existing.id(),
                existing.organization(),
                existing.managingRegion(),
                existing.title(),
                existing.description(),
                existing.projectType(),
                ProjectStatus.ACTIVE,
                existing.createdAt(),
                existing.deletedAt(),
                existing.deletedBy(),
                existing.locations(),
                existing.boundaries()
        ));

        auditService.recordProjectAudit(approved, actor, AuditAction.APPROVED);
        return approved;
    }

    @Override
    @Transactional
    @NonNull
    public Project rejectProject(@NonNull UUID id, @NonNull UUID actorUserId) {
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND));

        if (existing.deletedAt() != null) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND);
        }

        securityService.authorizeRegionalAdminApproval(actorUserId, id);

        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Project rejected = projectRepository.update(new Project(
                existing.id(),
                existing.organization(),
                existing.managingRegion(),
                existing.title(),
                existing.description(),
                existing.projectType(),
                ProjectStatus.REJECTED,
                existing.createdAt(),
                existing.deletedAt(),
                existing.deletedBy(),
                existing.locations(),
                existing.boundaries()
        ));

        auditService.recordProjectAudit(rejected, actor, AuditAction.REJECTED);
        return rejected;
    }
}
