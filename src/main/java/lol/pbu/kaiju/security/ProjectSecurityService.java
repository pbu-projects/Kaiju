package lol.pbu.kaiju.security;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.model.ProjectStatus;
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
    public ProjectStatus evaluateProjectCreation(UUID userId, UUID organizationId, String pointWkt) {
        // Validate WKT safely
        double lon, lat;
        try {
            Geometry geom = new WKTReader().read(pointWkt);
            if (!(geom instanceof Point p)) throw new IllegalArgumentException("WKT must be a Point");
            lon = p.getX();
            lat = p.getY();
        } catch (Exception e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid geographic coordinate format");
        }

        // 1. Check Org Manager permissions first (fixes logic bug where Region Agents were penalized)
        boolean isOrgVerified = queryRepository.isOrgVerified(organizationId);
        boolean isOrgManager = queryRepository.isOrgManager(userId, organizationId);

        if (isOrgVerified && isOrgManager) {
            boolean inOrgRegion = queryRepository.isPointInOrgRegion(organizationId, lon, lat);
            if (inOrgRegion) {
                return ProjectStatus.ACTIVE; // AUTO_APPROVED
            }
        }

        // 2. Check if user is a REGION_AGENT
        boolean isRegionAgent = queryRepository.isRegionAgent(userId);
        if (isRegionAgent) {
            boolean inAssignedRegion = queryRepository.isPointInAgentAssignedRegion(userId, lon, lat);
            if (inAssignedRegion) {
                return ProjectStatus.ACTIVE; // AUTO_APPROVED
            }
            // A Region Agent posting outside their boundary (and not as a valid Org Manager) is strictly forbidden
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Region Agents cannot post outside their boundaries without Org Manager privileges");
        }

        // 3. Fallback to manual queue for Standard Users or Org Managers outside their bounds
        return ProjectStatus.PENDING; // REQUIRES_REGIONAL_APPROVAL
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
