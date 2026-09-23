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
        ORDER BY o.name ASC
    """, countQuery = """
        SELECT COUNT(*)
        FROM organizations o
        WHERE LOWER(TRIM(o.name)) = LOWER(TRIM(:searchTerm))
    """)
    Page<Organization> searchByNameExact(String searchTerm, Pageable pageable);

    @Query(value = """
        SELECT o.id, o.name, o.website_url, o.parent_id, o.is_public, o.verification_status, o.verification_expires_at
        FROM organizations o
        WHERE LOWER(o.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
        ORDER BY
            CASE
                WHEN LOWER(TRIM(o.name)) = LOWER(TRIM(:searchTerm)) THEN 0
                WHEN LOWER(TRIM(REGEXP_REPLACE(o.name, '^(The|A|An)\\s+', '', 'i'))) = LOWER(TRIM(REGEXP_REPLACE(:searchTerm, '^(The|A|An)\\s+', '', 'i'))) THEN 0
                WHEN LOWER(REGEXP_REPLACE(o.name, '^(The|A|An)\\s+', '', 'i')) LIKE LOWER(CONCAT(REGEXP_REPLACE(TRIM(:searchTerm), '^(The|A|An)\\s+', '', 'i'), '%')) THEN 1
                WHEN LOWER(o.name) LIKE LOWER(CONCAT(TRIM(:searchTerm), '%')) THEN 1
                WHEN LOWER(o.name) LIKE LOWER(CONCAT('% ', TRIM(:searchTerm), '%')) THEN 2
                ELSE 3
            END,
            LENGTH(REGEXP_REPLACE(o.name, '^(The|A|An)\\s+', '', 'i')) ASC,
            o.name ASC
    """, countQuery = """
        SELECT COUNT(*)
        FROM organizations o
        WHERE LOWER(o.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
    """)
    Page<Organization> searchByNameRanked(String searchTerm, Pageable pageable);

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
            ORDER BY o.id, ST_Distance(l.geom, CAST(:point AS geography)) ASC
        ) o
        ORDER BY o.distance ASC
    """, countQuery = """
        SELECT COUNT(DISTINCT o.id)
        FROM organizations o
        INNER JOIN organization_locations ol ON o.id = ol.organization_id
        INNER JOIN locations l ON ol.location_id = l.id
        WHERE ST_DWithin(l.geom, CAST(:point AS geography), :radiusMeters)
    """)
    Page<Organization> searchByLocation(
            @TypeDef(type = DataType.OBJECT, converter = JtsPointConverter.class) Point point,
            double radiusMeters,
            Pageable pageable
    );
}

