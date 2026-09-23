package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.DataType;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.model.JtsPointConverter;
import org.jspecify.annotations.NonNull;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface OrganizationRepository extends PageableRepository<Organization, UUID> {
    @NonNull
    CursoredPage<Organization> findAll(@NonNull CursoredPageable pageable);

    @Query(value = """
        SELECT o.id, o.name, o.website_url, o.parent_id, o.is_public, o.verification_status, o.verification_expires_at
        FROM organizations o
        WHERE LOWER(TRIM(o.name)) = LOWER(TRIM(:searchTerm))
          AND o.is_public = TRUE
          AND o.verification_status = 'VERIFIED'
        ORDER BY o.name ASC
    """, countQuery = """
        SELECT COUNT(*)
        FROM organizations o
        WHERE LOWER(TRIM(o.name)) = LOWER(TRIM(:searchTerm))
          AND o.is_public = TRUE
          AND o.verification_status = 'VERIFIED'
    """)
    Page<Organization> searchByNameExact(@NonNull String searchTerm, @NonNull Pageable pageable);

    @Query(value = """
        SELECT o.id, o.name, o.website_url, o.parent_id, o.is_public, o.verification_status, o.verification_expires_at
        FROM organizations o
        WHERE o.is_public = TRUE
          AND o.verification_status = 'VERIFIED'
          AND (
              LOWER(o.name) LIKE LOWER(CONCAT('%', :escapedTerm, '%')) ESCAPE '\\'
              OR LOWER(o.name) LIKE LOWER(CONCAT('%', :escapedCanonical, '%')) ESCAPE '\\'
          )
        ORDER BY
            CASE
                WHEN LOWER(TRIM(o.name)) = LOWER(TRIM(:searchTerm)) THEN 0
                WHEN LOWER(TRIM(o.name)) = LOWER(TRIM(:canonicalTerm)) THEN 0
                WHEN LOWER(TRIM(REGEXP_REPLACE(o.name, '^(The|A|An)\\s+', '', 'i'))) = LOWER(TRIM(:canonicalTerm)) THEN 0
                WHEN LOWER(REGEXP_REPLACE(o.name, '^(The|A|An)\\s+', '', 'i')) LIKE LOWER(CONCAT(:escapedCanonical, '%')) ESCAPE '\\' THEN 1
                WHEN LOWER(o.name) LIKE LOWER(CONCAT(:escapedTerm, '%')) ESCAPE '\\' THEN 1
                WHEN LOWER(o.name) LIKE LOWER(CONCAT('% ', :escapedTerm, '%')) ESCAPE '\\' THEN 2
                WHEN LOWER(o.name) LIKE LOWER(CONCAT('% ', :escapedCanonical, '%')) ESCAPE '\\' THEN 2
                WHEN LOWER(o.name) LIKE LOWER(CONCAT('%', :escapedTerm, '%')) ESCAPE '\\' THEN 3
                WHEN LOWER(o.name) LIKE LOWER(CONCAT('%', :escapedCanonical, '%')) ESCAPE '\\' THEN 3
                ELSE 4
            END ASC,
            LENGTH(REGEXP_REPLACE(o.name, '^(The|A|An)\\s+', '', 'i')) ASC,
            o.name ASC
    """, countQuery = """
        SELECT COUNT(*)
        FROM organizations o
        WHERE o.is_public = TRUE
          AND o.verification_status = 'VERIFIED'
          AND (
              LOWER(o.name) LIKE LOWER(CONCAT('%', :escapedTerm, '%')) ESCAPE '\\'
              OR LOWER(o.name) LIKE LOWER(CONCAT('%', :escapedCanonical, '%')) ESCAPE '\\'
          )
    """)
    Page<Organization> searchByNameRanked(
            @NonNull String searchTerm,
            @NonNull String escapedTerm,
            @NonNull String canonicalTerm,
            @NonNull String escapedCanonical,
            @NonNull Pageable pageable
    );

    @Query(value = """
        SELECT o.id, o.name, o.website_url, o.parent_id, o.is_public, o.verification_status, o.verification_expires_at
        FROM organizations o
        WHERE o.is_public = TRUE
          AND o.verification_status = 'VERIFIED'
          AND (
              similarity(LOWER(o.name), LOWER(:searchTerm)) >= 0.3
              OR similarity(LOWER(o.name), LOWER(:canonicalTerm)) >= 0.3
          )
        ORDER BY
            GREATEST(similarity(LOWER(o.name), LOWER(:searchTerm)), similarity(LOWER(o.name), LOWER(:canonicalTerm))) DESC,
            LENGTH(REGEXP_REPLACE(o.name, '^(The|A|An)\\s+', '', 'i')) ASC,
            o.name ASC
    """, countQuery = """
        SELECT COUNT(*)
        FROM organizations o
        WHERE o.is_public = TRUE
          AND o.verification_status = 'VERIFIED'
          AND (
              similarity(LOWER(o.name), LOWER(:searchTerm)) >= 0.3
              OR similarity(LOWER(o.name), LOWER(:canonicalTerm)) >= 0.3
          )
    """)
    Page<Organization> searchByNameFuzzy(
            @NonNull String searchTerm,
            @NonNull String canonicalTerm,
            @NonNull Pageable pageable
    );

    @Query(value = """
        SELECT o.id, o.name, o.website_url, o.parent_id, o.is_public, o.verification_status, o.verification_expires_at
        FROM (
            SELECT DISTINCT ON (o.id)
                o.id, o.name, o.website_url, o.parent_id, o.is_public, o.verification_status, o.verification_expires_at,
                ST_Distance(l.geom, CAST(:point AS geography)) AS distance
            FROM organizations o
            INNER JOIN organization_locations ol ON o.id = ol.organization_id
            INNER JOIN locations l ON ol.location_id = l.id
            WHERE ST_DWithin(l.geom, CAST(:point AS geography), :radiusMeters)
              AND o.is_public = TRUE
              AND o.verification_status = 'VERIFIED'
            ORDER BY o.id, ST_Distance(l.geom, CAST(:point AS geography)) ASC
        ) o
        ORDER BY o.distance ASC
    """, countQuery = """
        SELECT COUNT(DISTINCT o.id)
        FROM organizations o
        INNER JOIN organization_locations ol ON o.id = ol.organization_id
        INNER JOIN locations l ON ol.location_id = l.id
        WHERE ST_DWithin(l.geom, CAST(:point AS geography), :radiusMeters)
          AND o.is_public = TRUE
          AND o.verification_status = 'VERIFIED'
    """)
    Page<Organization> searchByLocation(
            @NonNull @TypeDef(type = DataType.OBJECT, converter = JtsPointConverter.class) Point point,
            double radiusMeters,
            @NonNull Pageable pageable
    );

    @Query(value = """
        SELECT o.id, o.name, o.website_url, o.parent_id, o.is_public, o.verification_status, o.verification_expires_at
        FROM organizations o
        INNER JOIN organization_regions org_r ON o.id = org_r.organization_id
        WHERE org_r.region_id = :regionId
          AND o.is_public = TRUE
          AND o.verification_status = 'VERIFIED'
        ORDER BY o.name ASC
    """, countQuery = """
        SELECT COUNT(*)
        FROM organizations o
        INNER JOIN organization_regions org_r ON o.id = org_r.organization_id
        WHERE org_r.region_id = :regionId
          AND o.is_public = TRUE
          AND o.verification_status = 'VERIFIED'
    """)
    Page<Organization> searchByRegion(@NonNull UUID regionId, @NonNull Pageable pageable);
}

