package lol.pbu.kaiju.repository;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.Boundary;

import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface BoundaryRepository extends PageableRepository<Boundary, UUID> {
    @NonNull
    CursoredPage<Boundary> findAll(@NonNull CursoredPageable pageable);
}
