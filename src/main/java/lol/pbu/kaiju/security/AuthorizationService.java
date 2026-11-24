package lol.pbu.kaiju.security;

import io.micronaut.http.exceptions.HttpStatusException;
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
import lol.pbu.kaiju.repository.PermissionQueryRepository;
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
import java.util.List;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.FORBIDDEN;
import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.http.HttpStatus.UNAUTHORIZED;
import static lol.pbu.kaiju.model.ProjectStatus.ACTIVE;
import static lol.pbu.kaiju.model.ProjectStatus.PENDING;

@Singleton
public class AuthorizationService {

    private static final String USER_NOT_FOUND = "User not found";
    private static final String ACTOR_NOT_FOUND = "Actor user not found";
    private static final String OUTSIDE_LOCATION_REGION_ERROR =
            "Forbidden: Only system admins or region directors for this area may modify this location";
    private static final String OUTSIDE_BOUNDARY_REGION_ERROR =
            "Forbidden: Only system admins or region directors for this area may modify this boundary";

    private final PermissionQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final BoundaryRepository boundaryRepository;
    private final GeometryFactory geometryFactory;

    public AuthorizationService(
            PermissionQueryRepository queryRepository,
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

    /**
     * Core Security Matrix logic that evaluates if a user can create a project at a specific location,
     * and whether it should be AUTO_APPROVED or placed in the REQUIRES_REGIONAL_APPROVAL queue.
     */
    @NonNull
    public ProjectStatus evaluateProjectCreationByUser(@NonNull UUID userId, @NonNull Project project) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, USER_NOT_FOUND));

        UUID organizationId = project.organization() != null ? project.organization().id() : null;
        boolean isGlobalAdmin = user.role().hasPermission(Permission.SYSTEM_ADMIN);
        boolean isOrgMember = organizationId != null && queryRepository.isAnyOrgMember(userId, organizationId);
        boolean isRegionalAdmin = user.role().hasPermission(Permission.PROJECT_APPROVE)
                || user.role().hasPermission(Permission.REGION_MANAGE);

        boolean hasLocations = project.locations() != null && !project.locations().isEmpty();
        boolean inAssignedRegion = isInAssignedRegion(project, userId, hasLocations);
        boolean isAuthorizedRegionalAdmin = isRegionalAdmin && inAssignedRegion;

        if (!isGlobalAdmin && !isOrgMember && !isAuthorizedRegionalAdmin) {
            throw new HttpStatusException(FORBIDDEN, "User is not authorized to create a project for this organization");
        }

        if (project.status() == ProjectStatus.DRAFT) {
            return ProjectStatus.DRAFT;
        }

        if (organizationId == null || !hasLocations) {
            return PENDING;
        }

        if (isVerifiedOrgManagerInRegion(userId, organizationId, project)) {
            return ACTIVE;
        }

        if (!isGlobalAdmin && isRegionalAdmin) {
            if (inAssignedRegion) {
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
        if (project.locations() == null || project.locations().isEmpty()) {
            return false;
        }
        byte[] wkb = buildMultiPointWkb(project.locations());
        if (wkb.length == 0) {
            return false;
        }
        return queryRepository.areAllPointsInOrgRegion(organizationId, wkb);
    }

    public boolean areAllLocationsInOrgRegion(@NonNull Project project, @NonNull Organization organization) {
        if (organization.id() == null) {
            return false;
        }
        return areAllLocationsInOrgRegion(project, organization.id());
    }

    private boolean areAllLocationsInAssignedRegion(Project project, UUID userId) {
        if (project.locations() == null || project.locations().isEmpty()) {
            return false;
        }
        byte[] wkb = buildMultiPointWkb(project.locations());
        if (wkb.length == 0) {
            return false;
        }
        return queryRepository.areAllPointsInAgentAssignedRegion(userId, wkb);
    }

    public boolean areAllLocationsInRegion(@NonNull Project project, @NonNull UUID regionId) {
        if (project.locations() == null || project.locations().isEmpty()) {
            return false;
        }
        byte[] wkb = buildMultiPointWkb(project.locations());
        if (wkb.length == 0) {
            return false;
        }
        return queryRepository.areAllPointsInRegion(regionId, wkb);
    }

    @NonNull
    private byte[] buildMultiPointWkb(@NonNull List<Location> locations) {
        if (locations.isEmpty()) {
            return new byte[0];
        }
        Point[] points = new Point[locations.size()];
        for (int i = 0; i < locations.size(); i++) {
            Location loc = locations.get(i);
            if (loc.geom() == null) {
                return new byte[0];
            }
            points[i] = loc.geom();
        }
        MultiPoint multiPoint = geometryFactory.createMultiPoint(points);
        multiPoint.setSRID(4326);
        WKBWriter writer = new WKBWriter(2, true);
        return writer.write(multiPoint);
    }

    /**
     * Validates whether a user has authority to assign or reassign the project to a specific managing region.
     */
    public boolean canAssignManagingRegion(@NonNull UUID userId, @NonNull UUID organizationId, @NonNull UUID regionId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, USER_NOT_FOUND));
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
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, USER_NOT_FOUND));
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        if (canRegionalAdminModifyProject(user, project.id())) {
            return true;
        }
        if (project.organization() == null || project.organization().id() == null) {
            return false;
        }
        return queryRepository.isOrgManager(userId, project.organization().id());
    }

    private boolean canRegionalAdminModifyProject(User user, UUID projectId) {
        if (!user.role().hasPermission(Permission.PROJECT_UPDATE)) {
            return false;
        }
        boolean hasRegionalRole = user.role().hasPermission(Permission.PROJECT_APPROVE)
                || user.role().hasPermission(Permission.REGION_MANAGE);
        if (!hasRegionalRole) {
            return false;
        }
        return hasProjectJurisdiction(user, projectId);
    }

    private boolean hasProjectJurisdiction(User user, UUID projectId) {
        if (projectId == null) {
            return false;
        }
        long locationCount = queryRepository.countProjectLocations(projectId);
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
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, USER_NOT_FOUND));
        if (!user.role().hasPermission(Permission.PROJECT_REASSIGN)) {
            return false;
        }
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        return hasProjectJurisdiction(user, project.id());
    }

    /**
     * Enforces that a regional administrator (or global admin) has jurisdiction to approve a project.
     * For projects with locations, verifies that admin's region intersects ALL project locations.
     * For virtual projects (zero locations), permits REGION_DIRECTOR whose region contains the org/managing region.
     */
    public void authorizeRegionalAdminApproval(@NonNull UUID regionalAdminId, @NonNull UUID projectId) {
        var user = userRepository.findById(regionalAdminId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, USER_NOT_FOUND));
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return;
        }

        long locationCount = queryRepository.countProjectLocations(projectId);
        if (locationCount == 0) {
            if (!user.role().hasPermission(Permission.REGION_MANAGE)) {
                throw new HttpStatusException(FORBIDDEN, "Region Agents do not have jurisdiction to approve virtual projects.");
            }
            if (queryRepository.isVirtualProjectInDirectorJurisdiction(regionalAdminId, projectId)) {
                return;
            }
            throw new HttpStatusException(FORBIDDEN, "You do not have geographic jurisdiction to approve this virtual project.");
        }

        boolean hasJurisdiction = queryRepository.hasJurisdictionOverAllProjectLocations(regionalAdminId, projectId);
        if (!hasJurisdiction) {
            throw new HttpStatusException(FORBIDDEN, "You do not have geographic jurisdiction to approve this project.");
        }
    }

    public boolean isSystemAdmin(@Nullable UUID userId) {
        return hasPermission(userId, Permission.SYSTEM_ADMIN);
    }

    /**
     * Checks if a user can view a project.
     * Active non-deleted projects are visible to everyone; non-active projects are visible to
     * system admins, organization members, project sponsors, and regional admins with jurisdiction.
     */
    public boolean canViewProject(@Nullable UUID userId, @NonNull Project project) {
        if (project.deletedAt() != null) {
            return false;
        }
        if (project.status() == ACTIVE) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return false;
        }
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        if (project.organization() != null
                && project.organization().id() != null
                && queryRepository.isAnyOrgMember(userId, project.organization().id())) {
            return true;
        }
        if (project.id() != null && queryRepository.isProjectSponsor(userId, project.id())) {
            return true;
        }
        if (!isRegionalAdmin(user)) {
            return false;
        }
        if (hasProjectJurisdiction(user, project.id())) {
            return true;
        }
        return project.id() != null
                && queryRepository.countProjectLocations(project.id()) == 0
                && project.managingRegion() != null
                && project.managingRegion().id() != null
                && queryRepository.isUserInRegion(userId, project.managingRegion().id());
    }

    /**
     * Checks if a user can create, update, or delete shifts for a project.
     * Permits system admins, org managers/admins, regional admins with jurisdiction, and project sponsors.
     */
    public boolean canManageShift(@NonNull UUID userId, @NonNull Project project) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(UNAUTHORIZED, ACTOR_NOT_FOUND));
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
        if (!canManageShift(userId, project)) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: You do not have permission to manage shifts for this project");
        }
    }

    public boolean canViewShift(@Nullable UUID userId, @NonNull Shift shift) {
        return shift.project() != null && canViewProject(userId, shift.project());
    }

    public boolean canViewOrganization(@Nullable UUID userId, @NonNull Organization organization) {
        if (isPubliclyVisible(organization)) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        return isSystemAdmin(userId)
                || (organization.id() != null && queryRepository.isAnyOrgMember(userId, organization.id()));
    }

    public void authorizeOrganizationUpdate(@NonNull UUID userId, @NonNull UUID organizationId) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(UNAUTHORIZED, ACTOR_NOT_FOUND));
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
        if (isSystemAdmin(userId)) {
            return;
        }
        if (allowOrgManageUsers && hasPermission(userId, Permission.ORG_MANAGE_USERS)) {
            return;
        }
        if (queryRepository.isOrgAdmin(userId, organizationId)) {
            return;
        }
        throw new HttpStatusException(
                FORBIDDEN,
                "Forbidden: Only organization admins or system administrators may manage this organization"
        );
    }

    @NonNull
    public User authorizeOrganizationVerification(@NonNull UUID userId) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(UNAUTHORIZED, ACTOR_NOT_FOUND));
        if (!actor.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            throw new HttpStatusException(FORBIDDEN, "Only system admins can change organization verification status");
        }
        return actor;
    }

    public void authorizeRegionDirector(@NonNull UUID userId, @NonNull UUID regionId) {
        if (isSystemAdmin(userId) || queryRepository.isRegionDirector(userId, regionId)) {
            return;
        }
        throw new HttpStatusException(
                FORBIDDEN,
                "Forbidden: Only system admins or region directors of this region may manage region members"
        );
    }

    public void authorizeLocationCreate(@NonNull UUID userId, @Nullable Point point) {
        if (isSystemAdmin(userId)) {
            return;
        }
        if (point == null || !locationRepository.isPointInDirectorRegion(userId, point)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_LOCATION_REGION_ERROR);
        }
    }

    public void authorizeLocationUpdate(@NonNull UUID userId, @NonNull UUID locationId, @Nullable Point point) {
        if (isSystemAdmin(userId)) {
            return;
        }
        if (!locationRepository.isLocationInDirectorRegion(userId, locationId)
                || point == null
                || !locationRepository.isPointInDirectorRegion(userId, point)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_LOCATION_REGION_ERROR);
        }
    }

    public void authorizeLocationDelete(@NonNull UUID userId, @NonNull UUID locationId) {
        if (isSystemAdmin(userId)) {
            return;
        }
        if (!locationRepository.existsById(locationId)) {
            throw new HttpStatusException(NOT_FOUND, "Location not found");
        }
        if (!locationRepository.isLocationInDirectorRegion(userId, locationId)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_LOCATION_REGION_ERROR);
        }
    }

    public void authorizeBoundaryCreate(@NonNull UUID userId, @Nullable Geometry geom) {
        if (isSystemAdmin(userId)) {
            return;
        }
        if (geom == null || !boundaryRepository.isGeometryInDirectorRegion(userId, geom)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_BOUNDARY_REGION_ERROR);
        }
    }

    public void authorizeBoundaryUpdate(@NonNull UUID userId, @NonNull UUID boundaryId, @Nullable Geometry geom) {
        if (isSystemAdmin(userId)) {
            return;
        }
        if (!boundaryRepository.isBoundaryInDirectorRegion(userId, boundaryId)
                || geom == null
                || !boundaryRepository.isGeometryInDirectorRegion(userId, geom)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_BOUNDARY_REGION_ERROR);
        }
    }

    public void authorizeBoundaryDelete(@NonNull UUID userId, @NonNull UUID boundaryId) {
        if (isSystemAdmin(userId)) {
            return;
        }
        if (!boundaryRepository.existsById(boundaryId)) {
            throw new HttpStatusException(NOT_FOUND, "Boundary not found");
        }
        if (!boundaryRepository.isBoundaryInDirectorRegion(userId, boundaryId)) {
            throw new HttpStatusException(FORBIDDEN, OUTSIDE_BOUNDARY_REGION_ERROR);
        }
    }

    public void authorizeSelfOrUserManager(@NonNull UUID actorUserId, @NonNull UUID targetUserId) {
        if (actorUserId.equals(targetUserId) || hasUserManagePermission(actorUserId)) {
            return;
        }
        throw new HttpStatusException(FORBIDDEN, "Forbidden: You cannot modify or delete another user's account");
    }

    public void authorizeUserManager(@NonNull UUID actorUserId) {
        if (!hasUserManagePermission(actorUserId)) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: Only administrators can assign elevated user roles");
        }
    }

    private boolean isRegionalAdmin(User user) {
        return user.role().hasPermission(Permission.PROJECT_APPROVE)
                || user.role().hasPermission(Permission.REGION_MANAGE);
    }

    private boolean hasUserManagePermission(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.role().hasPermission(Permission.SYSTEM_USER_MANAGE)
                        || u.role().hasPermission(Permission.SYSTEM_ADMIN))
                .orElse(false);
    }

    private boolean hasPermission(@Nullable UUID userId, Permission permission) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(u -> u.role().hasPermission(permission))
                .orElse(false);
    }

    private boolean isPubliclyVisible(Organization org) {
        boolean notExpired = org.verificationExpiresAt() == null
                || org.verificationExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC));
        return Boolean.TRUE.equals(org.isPublic())
                && org.verificationStatus() == VerificationStatus.VERIFIED
                && notExpired;
    }
}
