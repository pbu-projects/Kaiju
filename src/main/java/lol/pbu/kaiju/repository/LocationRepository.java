package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.DataType;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.model.JtsPointConverter;
import org.jspecify.annotations.NonNull;
import org.locationtech.jts.geom.Point;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface LocationRepository extends PageableRepository<Location, UUID> {
    @NonNull
    CursoredPage<Location> findAll(@NonNull CursoredPageable pageable);

    long removeById(@NonNull UUID id);

    @Query(value = """
        SELECT tb.tzid
        FROM timezone_boundaries tb
        WHERE ST_Intersects(tb.geom, CAST(:point AS geometry))
        ORDER BY ST_Area(tb.geom) ASC
        LIMIT 1
    """, nativeQuery = true)
    Optional<String> findTimezoneByPoint(
            @NonNull @TypeDef(type = DataType.OBJECT, converter = JtsPointConverter.class) Point point
    );
}
