package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.repository.GenericRepository;
import lol.pbu.kaiju.domain.User;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface SecurityQueryRepository extends GenericRepository<User, UUID> {

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM region_users 
            WHERE user_id = :userId AND role = 'REGION_AGENT'
        )
    """)
    boolean isRegionAgent(UUID userId);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM administrative_regions r
            JOIN region_users ru ON ru.region_id = r.id
            WHERE ru.user_id = :userId AND ST_Intersects(r.geom, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326))
        )
    """)
    boolean isPointInAgentAssignedRegion(UUID userId, double longitude, double latitude);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM organizations 
            WHERE id = :organizationId AND verification_status = 'VERIFIED'
        )
    """)
    boolean isOrgVerified(UUID organizationId);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM organization_users 
            WHERE user_id = :userId AND organization_id = :organizationId AND role IN ('ORG_MANAGER', 'ORG_ADMIN')
        )
    """)
    boolean isOrgManager(UUID userId, UUID organizationId);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM administrative_regions r
            JOIN organization_regions or_reg ON or_reg.region_id = r.id
            WHERE or_reg.organization_id = :organizationId AND ST_Intersects(r.geom, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326))
        )
    """)
    boolean isPointInOrgRegion(UUID organizationId, double longitude, double latitude);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM projects p
            JOIN project_locations pl ON pl.project_id = p.id
            JOIN locations l ON l.id = pl.location_id
            JOIN region_users ru ON ru.user_id = :regionalAdminId AND ru.role IN ('REGION_AGENT', 'REGION_DIRECTOR')
            JOIN administrative_regions r ON r.id = ru.region_id
            WHERE p.id = :projectId AND ST_Intersects(r.geom, l.geom)
            -- Check that ALL locations of the project are within the admin's region
            GROUP BY p.id
            HAVING COUNT(DISTINCT l.id) = (
                SELECT COUNT(*) FROM project_locations pl2 WHERE pl2.project_id = p.id
            )
        )
    """)
    boolean hasJurisdictionOverAllProjectLocations(@NonNull UUID regionalAdminId, @NonNull UUID projectId);

    @Query("SELECT COUNT(*) FROM project_locations WHERE project_id = :projectId")
    long countProjectLocations(@NonNull UUID projectId);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM projects p
            JOIN region_users ru ON ru.user_id = :regionalAdminId AND ru.role = 'REGION_DIRECTOR'
            WHERE p.id = :projectId
              AND (
                  (p.managing_region_id IS NOT NULL AND ru.region_id = p.managing_region_id)
                  OR (
                      p.managing_region_id IS NULL
                      AND p.organization_id IS NOT NULL
                      AND EXISTS (
                          SELECT 1 FROM organization_regions or_reg
                          JOIN organizations o ON o.id = or_reg.organization_id
                          WHERE or_reg.organization_id = p.organization_id
                            AND or_reg.region_id = ru.region_id
                            AND o.verification_status = 'VERIFIED'
                      )
                  )
              )
        )
    """)
    boolean isVirtualProjectInDirectorJurisdiction(@NonNull UUID regionalAdminId, @NonNull UUID projectId);
}
