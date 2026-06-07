package lol.pbu.kaiju.core.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.core.domain.Project;
import lol.pbu.kaiju.core.model.ProjectSearchCard;

import java.util.List;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ProjectRepository extends PageableRepository<Project, UUID>{

    @Query("""
        SELECT DISTINCT ON (p.id, l.id)
            p.id AS project_id,
            p.title AS project_title,
            l.id AS location_id,
            l.name AS location_name,
            s.start_time AS next_shift_start
        FROM locations l
        INNER JOIN project_locations pl ON l.id = pl.location_id
        INNER JOIN projects p ON pl.project_id = p.id
        INNER JOIN shifts s ON (s.project_id = p.id AND s.location_id = l.id)
        WHERE p.status = 'ACTIVE'
          AND s.start_time >= NOW()
          AND ST_DWithin(l.geom, ST_MakePoint(:longitude, :latitude)\\:\\:geography, :radiusMeters)
        ORDER BY p.id, l.id, s.start_time ASC
    """)
    List<ProjectSearchCard> findLocalOpportunities(double longitude, double latitude, double radiusMeters);
}