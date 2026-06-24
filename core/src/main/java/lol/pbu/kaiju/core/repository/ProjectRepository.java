package lol.pbu.kaiju.core.repository;

import io.micronaut.data.annotation.Join;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.DataType;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.core.domain.Project;
import lol.pbu.kaiju.core.model.JtsPointConverter;
import lol.pbu.kaiju.core.model.ProjectSearchCard;
import org.locationtech.jts.geom.Point;

import java.util.Optional;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ProjectRepository extends PageableRepository<Project, UUID>{

    @Override
    @Join("organization")
    Optional<Project> findById(UUID id);

    @Join("organization")
    io.micronaut.data.model.CursoredPage<Project> findByTitle(String title, io.micronaut.data.model.CursoredPageable pageable);

    @Query(value = """
                SELECT project_.id AS project_id,
                       project_.project_title,
                       project_.location_id,
                       project_.location_name,
                       project_.next_shift_start
                FROM (
                    SELECT DISTINCT ON (p.id, l.id)
                        p.id AS id,
                        p.title AS project_title,
                        l.id AS location_id,
                        l.name AS location_name,
                        s.start_time AS next_shift_start,
                        l.geom AS geom
                    FROM locations l
                    INNER JOIN project_locations pl ON l.id = pl.location_id
                    INNER JOIN projects p ON pl.project_id = p.id
                    INNER JOIN shifts s ON (s.project_id = p.id AND s.location_id = l.id)
                    WHERE p.status = 'ACTIVE'
                      AND s.start_time >= NOW()
                      AND ST_DWithin(l.geom, CAST(:point AS geography), :radiusMeters)
                    ORDER BY p.id, l.id, s.start_time ASC
                ) project_
                ORDER BY ST_Distance(project_.geom, CAST(:point AS geography)) ASC
            """, countQuery = """
                SELECT COUNT(DISTINCT (p.id, l.id))
                FROM locations l
                INNER JOIN project_locations pl ON l.id = pl.location_id
                INNER JOIN projects p ON pl.project_id = p.id
                INNER JOIN shifts s ON (s.project_id = p.id AND s.location_id = l.id)
                WHERE p.status = 'ACTIVE'
                  AND s.start_time >= NOW()
                  AND ST_DWithin(l.geom, CAST(:point AS geography), :radiusMeters)
            """)
    io.micronaut.data.model.Page<ProjectSearchCard> searchByLocation(
            @TypeDef(type = DataType.OBJECT, converter = JtsPointConverter.class) Point point,
            double radiusMeters,
            io.micronaut.data.model.Pageable pageable
    );
}