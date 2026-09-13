package lol.pbu.kaiju.security;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.model.ProjectStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

@Singleton
public class ProjectSecurityService {

    private final DataSource dataSource;

    public ProjectSecurityService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Core Security Matrix logic that evaluates if a user can create a project at a specific location,
     * and whether it should be AUTO_APPROVED or placed in the REQUIRES_REGIONAL_APPROVAL queue.
     * Returns a string representing the state for testing, though in practice it would return a ProjectStatus.
     */
    public String evaluateProjectCreation(UUID userId, UUID organizationId, String pointWkt) {
        // 1. Is user a REGION_AGENT?
        boolean isRegionAgent = checkExists("""
            SELECT 1 FROM region_users 
            WHERE user_id = ? AND role = 'REGION_AGENT'
        """, userId);

        if (isRegionAgent) {
            // Check if the target point falls within ANY region assigned to this agent
            boolean inAssignedRegion = checkExists("""
                SELECT 1 FROM administrative_regions r
                JOIN region_users ru ON ru.region_id = r.id
                WHERE ru.user_id = ? AND ST_Intersects(r.geom, ST_GeogFromText(?))
            """, userId, pointWkt);
            
            return inAssignedRegion ? "AUTO_APPROVED" : "FORBIDDEN";
        }

        // 2. Is the Organization VERIFIED and is the user an ORG_MANAGER?
        boolean isOrgVerified = checkExists("""
            SELECT 1 FROM organizations 
            WHERE id = ? AND verification_status = 'VERIFIED'
        """, organizationId);
        
        boolean isOrgManager = checkExists("""
            SELECT 1 FROM organization_users 
            WHERE user_id = ? AND organization_id = ? AND role = 'ORG_MANAGER'
        """, userId, organizationId);

        if (isOrgVerified && isOrgManager) {
            // Check if the target point falls within ANY region the org is assigned to
            boolean inOrgRegion = checkExists("""
                SELECT 1 FROM administrative_regions r
                JOIN organization_regions or_reg ON or_reg.region_id = r.id
                WHERE or_reg.organization_id = ? AND ST_Intersects(r.geom, ST_GeogFromText(?))
            """, organizationId, pointWkt);
            
            if (inOrgRegion) {
                return "AUTO_APPROVED";
            }
        }

        // 3. Fallback to manual queue for Standard Users or Org Managers outside their bounds
        return "REQUIRES_REGIONAL_APPROVAL";
    }

    /**
     * Enforces that only a REGION_AGENT whose boundary intersects the project can approve it.
     */
    public void authorizeRegionalAdminApproval(UUID regionalAdminId, UUID projectId) {
        boolean hasJurisdiction = checkExists("""
            SELECT 1 FROM projects p
            JOIN project_locations pl ON pl.project_id = p.id
            JOIN locations l ON l.id = pl.location_id
            JOIN region_users ru ON ru.user_id = ? AND ru.role = 'REGION_AGENT'
            JOIN administrative_regions r ON r.id = ru.region_id
            WHERE p.id = ? AND ST_Intersects(r.geom, l.geom)
        """, regionalAdminId, projectId);

        if (!hasJurisdiction) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have geographic jurisdiction to approve this project.");
        }
    }

    private boolean checkExists(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement statement = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException("Database error checking geographic bounds", e);
        }
    }
}
