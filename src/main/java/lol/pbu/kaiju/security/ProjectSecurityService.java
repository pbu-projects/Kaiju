package lol.pbu.kaiju.security;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.repository.SecurityQueryRepository;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.WKTReader;

import java.util.UUID;

@Singleton
public class ProjectSecurityService {

    private final SecurityQueryRepository queryRepository;

    public ProjectSecurityService(SecurityQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    /**
     * Core Security Matrix logic that evaluates if a user can create a project at a specific location,
     * and whether it should be AUTO_APPROVED or placed in the REQUIRES_REGIONAL_APPROVAL queue.
     */
    public ProjectStatus evaluateProjectCreation(UUID userId, Project project) {
        UUID organizationId = project.organization() != null ? project.organization().id() : null;
        if (organizationId == null) {
            return ProjectStatus.PENDING;
        }

        // Check if project has no locations
        if (project.locations() == null || project.locations().isEmpty()) {
            return ProjectStatus.PENDING; // Manual queue if no geographic bounds provided
        }

        // 1. Check Org Manager permissions first
        boolean isOrgVerified = queryRepository.isOrgVerified(organizationId);
        boolean isOrgManager = queryRepository.isOrgManager(userId, organizationId);

        if (isOrgVerified && isOrgManager) {
            // Must be entirely within Org Region
            boolean allInOrgRegion = true;
            for (lol.pbu.kaiju.domain.Location loc : project.locations()) {
                if (loc.geom() == null || !queryRepository.isPointInOrgRegion(organizationId, loc.geom().getX(), loc.geom().getY())) {
                    allInOrgRegion = false;
                    break;
                }
            }
            if (allInOrgRegion) {
                return ProjectStatus.ACTIVE; // AUTO_APPROVED
            }
        }

        // 2. Check if user is a REGION_AGENT
        boolean isRegionAgent = queryRepository.isRegionAgent(userId);
        if (isRegionAgent) {
            boolean allInAssignedRegion = true;
            for (lol.pbu.kaiju.domain.Location loc : project.locations()) {
                if (loc.geom() == null || !queryRepository.isPointInAgentAssignedRegion(userId, loc.geom().getX(), loc.geom().getY())) {
                    allInAssignedRegion = false;
                    break;
                }
            }
            if (allInAssignedRegion) {
                return ProjectStatus.ACTIVE; // AUTO_APPROVED
            }
            // A Region Agent posting outside their boundary (and not as a valid Org Manager) is strictly forbidden
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Region Agents cannot post outside their boundaries without Org Manager privileges");
        }

        // 3. Fallback to manual queue for Standard Users or Org Managers outside their bounds
        return ProjectStatus.PENDING; // REQUIRES_REGIONAL_APPROVAL
    }

    /**
     * Helper to verify if a user is allowed to modify/delete a project.
     * Simple implementation: Must be Org Manager of the project's org.
     */
    public boolean canModifyProject(UUID userId, Project project) {
        if (project.organization() == null) return false;
        return queryRepository.isOrgManager(userId, project.organization().id());
    }

    /**
     * Enforces that only a REGION_AGENT whose boundary intersects ALL project locations can approve it.
     */
    public void authorizeRegionalAdminApproval(UUID regionalAdminId, UUID projectId) {
        boolean hasJurisdiction = queryRepository.hasJurisdictionOverAllProjectLocations(regionalAdminId, projectId);
        if (!hasJurisdiction) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have geographic jurisdiction to approve this project.");
        }
    }
}
