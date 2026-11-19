package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.DataType;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.Boundary;
import lol.pbu.kaiju.model.JtsPolygonConverter;
import org.jspecify.annotations.NonNull;
import org.locationtech.jts.geom.Geometry;

import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface BoundaryRepository extends PageableRepository<Boundary, UUID> {
    @NonNull
    CursoredPage<Boundary> findAll(@NonNull CursoredPageable pageable);

    long removeById(@NonNull UUID id);

    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM boundaries b
            JOIN region_users ru ON ru.user_id = :actorId AND ru.role = 'REGION_DIRECTOR'
            JOIN administrative_regions r ON r.id = ru.region_id
            WHERE b.id = :boundaryId
              AND ST_Intersects(r.geom, b.geom)
        )
    """, nativeQuery = true)
    boolean isBoundaryInDirectorRegion(@NonNull UUID actorId, @NonNull UUID boundaryId);

    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM administrative_regions r
            JOIN region_users ru ON ru.region_id = r.id
            WHERE ru.user_id = :actorId
              AND ru.role = 'REGION_DIRECTOR'
              AND ST_Intersects(r.geom, CAST(:geom AS geography))
        )
    """, nativeQuery = true)
    boolean isGeometryInDirectorRegion(
            @NonNull UUID actorId,
            @NonNull @TypeDef(type = DataType.OBJECT, converter = JtsPolygonConverter.class) Geometry geom
    );
}
