package lol.pbu.kaiju.security;

import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.model.UserRole;
import lol.pbu.kaiju.repository.SecurityQueryRepository;
import lol.pbu.kaiju.repository.UserRepository;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

import static io.micronaut.http.HttpStatus.FORBIDDEN;
import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static lol.pbu.kaiju.model.ProjectStatus.ACTIVE;
import static lol.pbu.kaiju.model.ProjectStatus.PENDING;

@Singleton
public class ProjectSecurityService {

    private final SecurityQueryRepository queryRepository;
    private final UserRepository userRepository;

    public ProjectSecurityService(SecurityQueryRepository queryRepository, UserRepository userRepository) {
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
    }

    /**
     * Core Security Matrix logic that evaluates if a user can create a project at a specific location,
     * and whether it should be AUTO_APPROVED or placed in the REQUIRES_REGIONAL_APPROVAL queue.
     */
    @NonNull
    public ProjectStatus evaluateProjectCreationByUser(@NonNull UUID userId, @NonNull Project project) {
        if (project.status() == ProjectStatus.DRAFT) {
            return ProjectStatus.DRAFT;
        }

        UUID organizationId = project.organization() != null ? project.organization().id() : null;
        if (organizationId == null) {
            return PENDING;
        }

        // Check if project has no locations
        if (project.locations() == null || project.locations().isEmpty()) {
            return PENDING; // Manual queue if no geographic bounds provided
        }

        // 1. Check Org Manager permissions first
        if (queryRepository.isOrgVerified(organizationId) &&
                queryRepository.isOrgManager(userId, organizationId) &&
                areAllLocationsInOrgRegion(project, organizationId)) {
            return ACTIVE; // AUTO_APPROVED
        }

        // 2. Check if user is a REGION_AGENT
        boolean isRegionAgent = queryRepository.isRegionAgent(userId);
        if (isRegionAgent) {
            if (areAllLocationsInAssignedRegion(project, userId)) {
                return ACTIVE; // AUTO_APPROVED
            }
            // A Region Agent posting outside their boundary (and not as a valid Org Manager) is strictly forbidden
            throw new HttpStatusException(FORBIDDEN, "Region Agents cannot post outside their boundaries without Org Manager privileges");
        }

        // 3. Fallback to manual queue for Standard Users or Org Managers outside their bounds
        return PENDING; // REQUIRES_REGIONAL_APPROVAL
    }

    public boolean areAllLocationsInOrgRegion(@NonNull Project project, @NonNull UUID organizationId) {
        if (project.locations() == null || project.locations().isEmpty()) {
            return false;
        }
        for (Location loc : project.locations()) {
            if (loc.geom() == null || !queryRepository.isPointInOrgRegion(organizationId, loc.geom().getX(), loc.geom().getY())) {
                return false;
            }
        }
        return true;
    }

    public boolean areAllLocationsInOrgRegion(@NonNull Project project, @NonNull Organization organization) {
        if (organization.id() == null) {
            return false;
        }
        return areAllLocationsInOrgRegion(project, organization.id());
    }

    private boolean areAllLocationsInAssignedRegion(Project project, UUID userId) {
        for (Location loc : project.locations()) {
            if (loc.geom() == null || !queryRepository.isPointInAgentAssignedRegion(userId, loc.geom().getX(), loc.geom().getY())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Helper to verify if a user is allowed to modify/delete a project.
     * System admins can modify anywhere; Regional admins can modify in-boundary;
     * Org managers/admins can modify their org's projects.
     */
    public boolean canModifyProject(@NonNull UUID userId, @NonNull Project project) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "User not found"));
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        if (user.role().hasPermission(Permission.PROJECT_UPDATE) &&
                (user.role() == UserRole.REGION_AGENT || user.role() == UserRole.REGION_DIRECTOR)) {
            if (project.id() != null && queryRepository.hasJurisdictionOverAllProjectLocations(userId, project.id())) {
                return true;
            }
        }
        if (project.organization() == null) {
            return false;
        }
        return queryRepository.isOrgManager(userId, project.organization().id());
    }

    /**
     * Helper to verify if a user is allowed to reassign a project to another organization.
     * Only users with PROJECT_REASSIGN permission can reassign project organization
     * (Global Admin anywhere; Regional Admins if they have jurisdiction).
     */
    public boolean canReassignProject(@NonNull UUID userId, @NonNull Project project) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "User not found"));
        if (!user.role().hasPermission(Permission.PROJECT_REASSIGN)) {
            return false;
        }
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return true;
        }
        return project.id() != null && queryRepository.hasJurisdictionOverAllProjectLocations(userId, project.id());
    }

    /**
     * Enforces that only a REGION_AGENT whose boundary intersects ALL project locations can approve it.
     */
    public void authorizeRegionalAdminApproval(@NonNull UUID regionalAdminId, @NonNull UUID projectId) {
        var user = userRepository.findById(regionalAdminId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "User not found"));
        if (user.role().hasPermission(Permission.SYSTEM_ADMIN)) {
            return;
        }

        boolean hasJurisdiction = queryRepository.hasJurisdictionOverAllProjectLocations(regionalAdminId, projectId);
        if (!hasJurisdiction) {
            throw new HttpStatusException(FORBIDDEN, "You do not have geographic jurisdiction to approve this project.");
        }
    }
}
