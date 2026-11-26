package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.repository.GenericRepository;
import lol.pbu.kaiju.domain.User;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface PermissionQueryRepository extends GenericRepository<User, UUID> {

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM organizations 
            WHERE id = :organizationId AND verification_status = 'VERIFIED'
              AND (verification_expires_at IS NULL OR verification_expires_at > NOW())
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
            SELECT 1 FROM organization_users 
            WHERE user_id = :userId AND organization_id = :organizationId
        )
    """)
    boolean isAnyOrgMember(UUID userId, UUID organizationId);

    @Query("""
        SELECT NOT ST_IsEmpty(ST_GeomFromWKB(:wkb, 4326))
           AND NOT EXISTS (
            SELECT 1 FROM ST_Dump(ST_GeomFromWKB(:wkb, 4326)) d
            WHERE NOT EXISTS (
                SELECT 1 FROM administrative_regions r
                JOIN organization_regions or_reg ON or_reg.region_id = r.id
                WHERE or_reg.organization_id = :organizationId
                  AND ST_Intersects(r.geom, d.geom)
            )
        )
    """)
    boolean areAllPointsInOrgRegion(UUID organizationId, byte[] wkb);

    @Query("""
        SELECT NOT ST_IsEmpty(ST_GeomFromWKB(:wkb, 4326))
           AND NOT EXISTS (
            SELECT 1 FROM ST_Dump(ST_GeomFromWKB(:wkb, 4326)) d
            WHERE NOT EXISTS (
                SELECT 1 FROM administrative_regions r
                JOIN region_users ru ON ru.region_id = r.id
                WHERE ru.user_id = :userId
                  AND ST_Intersects(r.geom, d.geom)
            )
        )
    """)
    boolean areAllPointsInAgentAssignedRegion(UUID userId, byte[] wkb);

    @Query("""
        SELECT NOT ST_IsEmpty(ST_GeomFromWKB(:wkb, 4326))
           AND NOT EXISTS (
            SELECT 1 FROM ST_Dump(ST_GeomFromWKB(:wkb, 4326)) d
            WHERE NOT EXISTS (
                SELECT 1 FROM administrative_regions r
                WHERE r.id = :regionId
                  AND ST_Intersects(r.geom, d.geom)
            )
        )
    """)
    boolean areAllPointsInRegion(UUID regionId, byte[] wkb);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM project_users 
            WHERE user_id = :userId AND project_id = :projectId AND role = 'PROJECT_SPONSOR'
        )
    """)
    boolean isProjectSponsor(UUID userId, UUID projectId);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM organization_users 
            WHERE user_id = :userId AND organization_id = :organizationId AND role = 'ORG_ADMIN'
        )
    """)
    boolean isOrgAdmin(UUID userId, UUID organizationId);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM organization_regions 
            WHERE organization_id = :organizationId AND region_id = :regionId
        )
    """)
    boolean isOrgInRegion(UUID organizationId, UUID regionId);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM region_users 
            WHERE user_id = :userId AND region_id = :regionId AND role IN ('REGION_DIRECTOR', 'REGION_AGENT')
        )
    """)
    boolean isUserInRegion(UUID userId, UUID regionId);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM region_users 
            WHERE user_id = :userId AND region_id = :regionId AND role = 'REGION_DIRECTOR'
        )
    """)
    boolean isRegionDirector(UUID userId, UUID regionId);

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
                            AND (o.verification_expires_at IS NULL OR o.verification_expires_at > NOW())
                      )
                  )
              )
        )
    """)
    boolean isVirtualProjectInDirectorJurisdiction(@NonNull UUID regionalAdminId, @NonNull UUID projectId);

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM organization_regions or_reg
            JOIN organizations o ON o.id = or_reg.organization_id
            JOIN region_users ru ON ru.region_id = or_reg.region_id
            WHERE or_reg.organization_id = :organizationId
              AND ru.user_id = :regionalAdminId
              AND ru.role = 'REGION_DIRECTOR'
              AND o.verification_status = 'VERIFIED'
              AND (o.verification_expires_at IS NULL OR o.verification_expires_at > NOW())
        )
    """)
    boolean isVerifiedOrgInDirectorRegion(@NonNull UUID regionalAdminId, @NonNull UUID organizationId);
}
