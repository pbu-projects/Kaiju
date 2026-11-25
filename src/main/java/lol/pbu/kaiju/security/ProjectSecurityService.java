package lol.pbu.kaiju.security;

import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.Shift;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.model.VerificationStatus;
import lol.pbu.kaiju.repository.BoundaryRepository;
import lol.pbu.kaiju.repository.LocationRepository;
import lol.pbu.kaiju.repository.SecurityQueryRepository;
import lol.pbu.kaiju.repository.UserRepository;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.WKBWriter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.FORBIDDEN;
import static io.micronaut.http.HttpStatus.UNAUTHORIZED;
import static lol.pbu.kaiju.model.ProjectStatus.ACTIVE;
import static lol.pbu.kaiju.model.ProjectStatus.PENDING;

@Singleton
@Transactional(readOnly = true)
public class ProjectSecurityService {

    private static final String ACTOR_NOT_FOUND = "Actor user not found";
    private static final String OUTSIDE_LOCATION_REGION_ERROR =
            "Forbidden: Only system admins or region directors for this area may modify this location";
    private static final String OUTSIDE_BOUNDARY_REGION_ERROR =
            "Forbidden: Only system admins or region directors for this area may modify this boundary";

    private final SecurityQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final BoundaryRepository boundaryRepository;
    private final GeometryFactory geometryFactory;

    public ProjectSecurityService(
            SecurityQueryRepository queryRepository,
            UserRepository userRepository,
            LocationRepository locationRepository,
            BoundaryRepository boundaryRepository,
            GeometryFactory geometryFactory
    ) {
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
        this.locationRepository = locationRepository;
        this.boundaryRepository = boundaryRepository;
        this.geometryFactory = geometryFactory;
    }

    private record CreationAuthority(
            boolean isGlobalAdmin,
            boolean isRegionalAdmin,
            boolean hasLocations,
            boolean inAssignedRegion
    ) {}

    public void authorizeProjectCreation(@NonNull User user, @NonNull Project project) {
        requireCreationAuthority(user, project);
    }

    private CreationAuthority requireCreationAuthority(User user, Project project) {
        UUID userId = user.id();
        UUID organizationId = project.organization() != null ? project.organization().id() : null;
        boolean isGlobalAdmin = user.role().hasPermission(Permission.SYSTEM_ADMIN);
        boolean isOrgMember = organizationId != null && queryRepository.isAnyOrgMember(userId, organizationId);
        boolean isRegionalAdmin = isRegionalAdmin(user);

        boolean hasLocations = project.locations() != null && !project.locations().isEmpty();
        boolean inAssignedRegion = isInAssignedRegion(project, userId, hasLocations);
        boolean isAuthorizedRegionalAdmin = isRegionalAdmin && inAssignedRegion;

        if (!isGlobalAdmin && !isOrgMember && !isAuthorizedRegionalAdmin) {
            throw new HttpStatusException(FORBIDDEN, "User is not authorized to create a project for this organization");
        }
        return new CreationAuthority(isGlobalAdmin, isRegionalAdmin, hasLocations, inAssignedRegion);
    }

    /**
     * Core Security Matrix logic that evaluates if a user can create a project at a specific location,
     * and whether it should be AUTO_APPROVED or placed in the REQUIRES_REGIONAL_APPROVAL queue.
     */
    @NonNull
    public ProjectStatus evaluateProjectCreationByUser(@NonNull UUID userId, @NonNull Project project) {
        return evaluateProjectCreationByUser(requireActor(userId), project);
    }

    @NonNull
    public ProjectStatus evaluateProjectCreationByUser(@NonNull User user, @NonNull Project project) {
        CreationAuthority auth = requireCreationAuthority(user, project);
        UUID userId = user.id();
        UUID organizationId = project.organization() != null ? project.organization().id() : null;

        if (project.status() == ProjectStatus.DRAFT) {
            return ProjectStatus.DRAFT;
        }

        if (organizationId == null || !auth.hasLocations()) {
            return PENDING;
        }

        if (isVerifiedOrgManagerInRegion(userId, organizationId, project)) {
            return ACTIVE;
        }

        if (!auth.isGlobalAdmin() && auth.isRegionalAdmin()) {
            if (auth.inAssignedRegion()) {
                return ACTIVE;
            }
            throw new HttpStatusException(FORBIDDEN, "Region Agents cannot post outside their boundaries without Org Manager privileges");
        }

        return PENDING;
    }

    private boolean isInAssignedRegion(Project project, UUID userId, boolean hasLocations) {
        if (hasLocations) {
            return areAllLocationsInAssignedRegion(project, userId);
        }
        return project.managingRegion() != null
                && project.managingRegion().id() != null
                && queryRepository.isUserInRegion(userId, project.managingRegion().id());
    }

    private boolean isVerifiedOrgManagerInRegion(UUID userId, UUID organizationId, Project project) {
        return queryRepository.isOrgVerified(organizationId)
                && queryRepository.isOrgManager(userId, organizationId)
                && areAllLocationsInOrgRegion(project, organizationId);
    }

    public boolean areAllLocationsInOrgRegion(@NonNull Project project, @NonNull UUID organizationId) {
        return buildMultiPointWkb(project)
                .map(wkb -> queryRepository.areAllPointsInOrgRegion(organizationId, wkb))
                .orElse(false);
    }

    private boolean areAllLocationsInAssignedRegion(Project project, UUID userId) {
        return buildMultiPointWkb(project)
                .map(wkb -> queryRepository.areAllPointsInAgentAssignedRegion(userId, wkb))
                .orElse(false);
    }

    public boolean areAllLocationsInRegion(@NonNull Project project, @NonNull UUID regionId) {
        return buildMultiPointWkb(project)
                .map(wkb -> queryRepository.areAllPointsInRegion(regionId, wkb))
                .orElse(false);
    }

    private Optional<byte[]> buildMultiPointWkb(@NonNull Project project) {
        List<Location> locations = project.locations();
        if (locations == null || locations.isEmpty() || locations.stream().anyMatch(loc -> loc.geom() == null)) {
            return Optional.empty();
        }
        Point[] points = locations.stream()
                .map(Location::geom)
                .toArray(Point[]::new);
        MultiPoint multiPoint = geometryFactory.createMultiPoint(points);
        multiPoint.setSRID(4326);
        WKBWriter writer = new WKBWriter(2, true);
        return Optional.of(writer.write(multiPoint));
    }

    /**
     * Validates whether a user has authority to assign or reassign the project to a specific managing region.
     */
    public boolean canAssignManagingRegion(@NonNull UUID userId, @NonNull UUID organizationId, @NonNull UUID regionId) {
        return canAssignManagingRegion(requireActor(userId), organizationId, regionId);
    }

    public boolean canAssignManagingRegion(@NonNull User user, @NonNull UUID organizationId, @NonNull UUID regionId) {
        UUID userId = user.id();
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        boolean hasRegionalPermission = user.role().hasPermission(Permission.PROJECT_REASSIGN)
                || user.role().hasPermission(Permission.REGION_MANAGE)
                || user.role().hasPermission(Permission.PROJECT_APPROVE);
        if (hasRegionalPermission && queryRepository.isUserInRegion(userId, regionId)) {
            return true;
        }
        return queryRepository.isOrgManager(userId, organizationId) && queryRepository.isOrgInRegion(organizationId, regionId);
    }

    /**
     * Helper to verify if a user is allowed to modify/delete a project.
     * System admins can modify anywhere; Regional admins can modify in-boundary;
     * Org managers/admins can modify their org's projects.
     */
    public boolean canModifyProject(@NonNull UUID userId, @NonNull Project project) {
        return canModifyProject(requireActor(userId), project);
    }

    public boolean canModifyProject(@NonNull User user, @NonNull Project project) {
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        if (canRegionalAdminModifyProject(user, project.id())) {
            return true;
        }
        if (project.organization() == null || project.organization().id() == null) {
            return false;
        }
        return queryRepository.isOrgManager(user.id(), project.organization().id());
    }

    private boolean canRegionalAdminModifyProject(User user, UUID projectId) {
        if (!user.role().hasPermission(Permission.PROJECT_UPDATE) || !isRegionalAdmin(user)) {
            return false;
        }
        return hasProjectJurisdiction(user, projectId);
    }

    private boolean hasProjectJurisdiction(User user, UUID projectId) {
        if (projectId == null) {
            return false;
        }
        return hasProjectJurisdiction(user, projectId, queryRepository.countProjectLocations(projectId));
    }

    private boolean hasProjectJurisdiction(User user, UUID projectId, long locationCount) {
        if (locationCount == 0) {
            return user.role().hasPermission(Permission.REGION_MANAGE)
                    && queryRepository.isVirtualProjectInDirectorJurisdiction(user.id(), projectId);
        }
        return queryRepository.hasJurisdictionOverAllProjectLocations(user.id(), projectId);
    }

    /**
     * Helper to verify if a user is allowed to reassign a project to another organization.
     * Only users with PROJECT_REASSIGN permission can reassign project organization
     * (Global Admin anywhere; Regional Admins if they have jurisdiction).
     */
    public boolean canReassignProject(@NonNull UUID userId, @NonNull Project project) {
        return canReassignProject(requireActor(userId), project);
    }

    public boolean canReassignProject(@NonNull User user, @NonNull Project project) {
        if (!user.role().hasPermission(Permission.PROJECT_REASSIGN)) {
            return false;
        }
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        return hasProjectJurisdiction(user, project.id());
    }

    /**
     * Enforces that a regional administrator (or global admin) has jurisdiction to approve or reject a project.
     * For projects with locations, verifies that admin's region intersects ALL project locations.
     * For virtual projects (zero locations), permits REGION_DIRECTOR whose region contains the org/managing region.
     */
    @NonNull
    public User authorizeRegionalAdminApproval(@NonNull UUID regionalAdminId, @NonNull UUID projectId) {
        User actor = requireActor(regionalAdminId);
        if (actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return actor;
        }
        if (!actor.role().hasPermission(Permission.PROJECT_APPROVE)) {
            throw new HttpStatusException(FORBIDDEN, "You do not have permission to approve or reject projects.");
        }

        long locationCount = queryRepository.countProjectLocations(projectId);
        if (locationCount == 0) {
            if (!actor.role().hasPermission(Permission.REGION_MANAGE)) {
                throw new HttpStatusException(FORBIDDEN, "Region Agents do not have jurisdiction to approve virtual projects.");
            }
            if (queryRepository.isVirtualProjectInDirectorJurisdiction(regionalAdminId, projectId)) {
                return actor;
            }
            throw new HttpStatusException(FORBIDDEN, "You do not have geographic jurisdiction to approve this virtual project.");
        }

        boolean hasJurisdiction = queryRepository.hasJurisdictionOverAllProjectLocations(regionalAdminId, projectId);
        if (!hasJurisdiction) {
            throw new HttpStatusException(FORBIDDEN, "You do not have geographic jurisdiction to approve this project.");
        }
        return actor;
    }

    public boolean isSystemAdmin(@Nullable UUID userId) {
        return hasAnyPermission(userId, Permission.SYSTEM_ADMIN);
    }

    public void authorizeSystemAdmin(@NonNull UUID userId) {
        if (!isSystemAdmin(userId)) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: Only system administrators may perform this action");
        }
    }

    /**
     * Checks if a user can view a project.
     * Active non-deleted projects are visible to everyone; non-active projects are visible to
     * system admins, organization members, project sponsors, and regional admins with jurisdiction.
     */
    public boolean canViewProject(@Nullable UUID userId, @NonNull Project project) {
        User user = userId != null ? requireActor(userId) : null;
        if (project.deletedAt() != null) {
            return false;
        }
        if (project.status() == ACTIVE) {
            return true;
        }
        if (user == null) {
            return false;
        }
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        if (project.organization() != null
                && project.organization().id() != null
                && queryRepository.isAnyOrgMember(user.id(), project.organization().id())) {
            return true;
        }
        if (project.id() != null && queryRepository.isProjectSponsor(user.id(), project.id())) {
            return true;
        }
        if (!isRegionalAdmin(user) || project.id() == null) {
            return false;
        }
        long locationCount = queryRepository.countProjectLocations(project.id());
        if (hasProjectJurisdiction(user, project.id(), locationCount)) {
            return true;
        }
        return locationCount == 0
                && project.managingRegion() != null
                && project.managingRegion().id() != null
                && queryRepository.isUserInRegion(user.id(), project.managingRegion().id());
    }

    /**
     * Checks if a user can create, update, or delete shifts for a project.
     * Permits system admins, org managers/admins, regional admins with jurisdiction, and project sponsors.
     */
    public boolean canManageShift(@NonNull UUID userId, @NonNull Project project) {
        return canManageShift(requireActor(userId), project);
    }

    public boolean canManageShift(@NonNull User actor, @NonNull Project project) {
        UUID userId = actor.id();
        if (actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        if (project.organization() != null
                && project.organization().id() != null
                && queryRepository.isOrgManager(userId, project.organization().id())) {
            return true;
        }
        if (isRegionalAdmin(actor) && hasProjectJurisdiction(actor, project.id())) {
            return true;
        }
        return project.id() != null && queryRepository.isProjectSponsor(userId, project.id());
    }

    public void authorizeShiftManagement(@NonNull UUID userId, @NonNull Project project) {
        authorizeShiftManagement(requireActor(userId), project);
    }

    public void authorizeShiftManagement(@NonNull User actor, @NonNull Project project) {
        if (!canManageShift(actor, project)) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: You do not have permission to manage shifts for this project");
        }
    }

    public boolean canViewShift(@Nullable UUID userId, @NonNull Shift shift) {
        if (shift.project() == null) {
            if (userId != null) {
                requireActor(userId);
            }
            return false;
        }
        return canViewProject(userId, shift.project());
    }

    public void authorizeShiftView(@Nullable UUID userId, @NonNull Shift shift) {
        if (!canViewShift(userId, shift)) {
            if (userId == null) {
                throw new HttpStatusException(UNAUTHORIZED, ACTOR_NOT_FOUND);
            }
            throw new HttpStatusException(FORBIDDEN, "Forbidden: You do not have permission to view this shift");
        }
    }

    public boolean canViewOrganization(@Nullable UUID userId, @NonNull Organization organization) {
        User user = userId != null ? requireActor(userId) : null;
        if (isPubliclyVisible(organization)) {
            return true;
        }
        if (user == null) {
            return false;
        }
        return user.role().hasPermission(Permission.SYSTEM_ADMIN)
                || (organization.id() != null && queryRepository.isAnyOrgMember(user.id(), organization.id()));
    }

    public void authorizeOrganizationView(@Nullable UUID actorUserId, @NonNull Organization organization) {
        if (!canViewOrganization(actorUserId, organization)) {
            if (actorUserId == null) {
                throw new HttpStatusException(UNAUTHORIZED, ACTOR_NOT_FOUND);
            }
            throw new HttpStatusException(FORBIDDEN, "Forbidden: You do not have permission to view this organization");
        }
    }

    public void authorizeOrganizationUpdate(@NonNull UUID userId, @NonNull UUID organizationId) {
        User actor = requireActor(userId);
        if (actor.role().hasPermission(Permission.SYSTEM_ADMIN)
                || queryRepository.isOrgManager(userId, organizationId)) {
            return;
        }
        throw new HttpStatusException(FORBIDDEN, "Only global admins or organization managers can update this organization");
    }

    public void authorizeOrganizationAdmin(@NonNull UUID userId, @NonNull UUID organizationId) {
        authorizeOrganizationAdmin(userId, organizationId, false);
    }

    public void authorizeOrganizationAdmin(
            @NonNull UUID userId,
            @NonNull UUID organizationId,
            boolean allowOrgManageUsers
    ) {
        authorizeOrganizationAdmin(requireActor(userId), organizationId, allowOrgManageUsers);
    }

    private void authorizeOrganizationAdmin(User actor, UUID organizationId, boolean allowOrgManageUsers) {
        if (actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return;
        }
        if (allowOrgManageUsers && actor.role().hasPermission(Permission.ORG_MANAGE_USERS)) {
            return;
        }
        if (queryRepository.isOrgAdmin(actor.id(), organizationId)) {
            return;
        }
        throw new HttpStatusException(
                FORBIDDEN,
                "Forbidden: Only organization admins or system administrators may manage this organization"
        );
    }

    public void authorizeOrganizationUserView(
            @NonNull UUID actorUserId,
            @NonNull UUID targetUserId,
            @NonNull UUID organizationId
    ) {
        User actor = requireActor(actorUserId);
        if (actorUserId.equals(targetUserId)) {
            return;
        }
        authorizeOrganizationAdmin(actor, organizationId, true);
    }

    @NonNull
    public User authorizeOrganizationVerification(@NonNull UUID userId) {
        User actor = requireActor(userId);
        if (!actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            throw new HttpStatusException(FORBIDDEN, "Only system admins can change organization verification status");
        }
        return actor;
    }

    public void authorizeRegionDirector(@NonNull UUID userId, @NonNull UUID regionId) {
        authorizeRegionDirector(requireActor(userId), regionId);
    }

    private void authorizeRegionDirector(User actor, UUID regionId) {
        if (actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return;
        }
        if (!actor.role().hasPermission(Permission.REGION_MANAGE)
                || !queryRepository.isRegionDirector(actor.id(), regionId)) {
            throw new HttpStatusException(
                    FORBIDDEN,
                    "Forbidden: Only system admins or region directors of this region may manage region members"
            );
        }
    }

    public void authorizeRegionUserView(
            @NonNull UUID actorUserId,
            @NonNull UUID targetUserId,
            @NonNull UUID regionId
    ) {
        User actor = requireActor(actorUserId);
        if (actorUserId.equals(targetUserId)) {
            return;
        }
        authorizeRegionDirector(actor, regionId);
    }

    public void authorizeLocationCreate(@NonNull UUID userId, @Nullable Point point) {
        if (isSystemAdminForRegionAction(userId, OUTSIDE_LOCATION_REGION_ERROR)) {
            return;
        }
        if (point == null || !locationRepository.isPointInDirectorRegion(userId, point)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_LOCATION_REGION_ERROR);
        }
    }

    public void authorizeLocationUpdate(@NonNull UUID userId, @NonNull UUID locationId, @Nullable Point point) {
        if (isSystemAdminForRegionAction(userId, OUTSIDE_LOCATION_REGION_ERROR)) {
            return;
        }
        if (!locationRepository.isLocationInDirectorRegion(userId, locationId)
                || point == null
                || !locationRepository.isPointInDirectorRegion(userId, point)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_LOCATION_REGION_ERROR);
        }
    }

    public void authorizeLocationDelete(@NonNull UUID userId, @NonNull UUID locationId) {
        if (isSystemAdminForRegionAction(userId, OUTSIDE_LOCATION_REGION_ERROR)) {
            return;
        }
        if (!locationRepository.isLocationInDirectorRegion(userId, locationId)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_LOCATION_REGION_ERROR);
        }
    }

    public void authorizeBoundaryCreate(@NonNull UUID userId, @Nullable Geometry geom) {
        if (isSystemAdminForRegionAction(userId, OUTSIDE_BOUNDARY_REGION_ERROR)) {
            return;
        }
        if (geom == null || !boundaryRepository.isGeometryInDirectorRegion(userId, geom)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_BOUNDARY_REGION_ERROR);
        }
    }

    public void authorizeBoundaryUpdate(@NonNull UUID userId, @NonNull UUID boundaryId, @Nullable Geometry geom) {
        if (isSystemAdminForRegionAction(userId, OUTSIDE_BOUNDARY_REGION_ERROR)) {
            return;
        }
        if (!boundaryRepository.isBoundaryInDirectorRegion(userId, boundaryId)
                || geom == null
                || !boundaryRepository.isGeometryInDirectorRegion(userId, geom)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_BOUNDARY_REGION_ERROR);
        }
    }

    public void authorizeBoundaryDelete(@NonNull UUID userId, @NonNull UUID boundaryId) {
        if (isSystemAdminForRegionAction(userId, OUTSIDE_BOUNDARY_REGION_ERROR)) {
            return;
        }
        if (!boundaryRepository.isBoundaryInDirectorRegion(userId, boundaryId)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_BOUNDARY_REGION_ERROR);
        }
    }

    private boolean isSystemAdminForRegionAction(@NonNull UUID userId, String forbiddenMessage) {
        User actor = requireActor(userId);
        if (actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        if (!actor.role().hasPermission(Permission.REGION_MANAGE)) {
            throw new HttpStatusException(FORBIDDEN, forbiddenMessage);
        }
        return false;
    }

    public void authorizeSelfOrUserManager(@NonNull UUID actorUserId, @NonNull UUID targetUserId) {
        User actor = requireActor(actorUserId);
        if (actorUserId.equals(targetUserId)
                || actor.role().hasPermission(Permission.SYSTEM_USER_MANAGE)
                || actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return;
        }
        throw new HttpStatusException(FORBIDDEN, "Forbidden: You cannot modify or delete another user's account");
    }

    public void authorizeUserManager(@NonNull UUID actorUserId) {
        if (!hasAnyPermission(actorUserId, Permission.SYSTEM_USER_MANAGE, Permission.SYSTEM_ADMIN)) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: Only administrators can assign elevated user roles");
        }
    }

    @NonNull
    public User requireActor(@NonNull UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(UNAUTHORIZED, ACTOR_NOT_FOUND));
    }

    private boolean isRegionalAdmin(User user) {
        return user.role().hasPermission(Permission.PROJECT_APPROVE)
                || user.role().hasPermission(Permission.REGION_MANAGE);
    }

    private boolean hasAnyPermission(@Nullable UUID userId, Permission... permissions) {
        if (userId == null) {
            return false;
        }
        User actor = requireActor(userId);
        return Arrays.stream(permissions).anyMatch(actor.role()::hasPermission);
    }

    private boolean isPubliclyVisible(Organization org) {
        boolean notExpired = org.verificationExpiresAt() == null
                || org.verificationExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC));
        return Boolean.TRUE.equals(org.isPublic())
                && org.verificationStatus() == VerificationStatus.VERIFIED
                && notExpired;
    }
}
