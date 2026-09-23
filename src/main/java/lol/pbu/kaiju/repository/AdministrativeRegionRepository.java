package lol.pbu.kaiju.repository;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface AdministrativeRegionRepository extends PageableRepository<AdministrativeRegion, UUID> {
    @NonNull
    CursoredPage<AdministrativeRegion> findAll(@NonNull CursoredPageable pageable);
}
