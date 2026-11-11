package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import lol.pbu.kaiju.dto.CoveringRegionDto;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface AdministrativeRegionRepository extends PageableRepository<AdministrativeRegion, UUID> {

    @NonNull
    CursoredPage<AdministrativeRegion> findAll(@NonNull CursoredPageable pageable);

    long removeById(@NonNull UUID id);

    @Query(value = """
        SELECT id, name, level, country_code, state_code, time_zone
        FROM administrative_regions
        WHERE ST_Covers(geom, ST_SetSRID(ST_Point(:lon, :lat), 4326)::geography)
        ORDER BY level DESC NULLS LAST, ST_Area(geom) ASC
    """, nativeQuery = true)
    List<CoveringRegionDto> findCoveringRegions(double lat, double lon);

    List<AdministrativeRegion> findByLevelOrderByNameAsc(int level);

    List<AdministrativeRegion> findByParentRegionIdOrderByNameAsc(UUID parentRegionId);

    List<AdministrativeRegion> findByLevelAndStateCodeIgnoreCaseOrderByNameAsc(int level, String stateCode);

    Optional<AdministrativeRegion> findFirstByLevelAndStateCodeIgnoreCase(int level, String stateCode);
}
