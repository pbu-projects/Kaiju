package lol.pbu.kaiju.repository;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.annotation.Join;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Shift;

import java.util.Optional;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ShiftRepository extends PageableRepository<Shift, UUID> {

    @NonNull
    @Join("project")
    @Join("project.organization")
    @Join(value = "location", type = Join.Type.LEFT_FETCH)
    CursoredPage<Shift> findAll(@NonNull @Valid CursoredPageable pageable);

    @NonNull
    @Join("project")
    @Join("project.organization")
    @Join(value = "location", type = Join.Type.LEFT_FETCH)
    Optional<Shift> findById(@NonNull UUID id);
}