package lol.pbu.kaiju.repository;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.User;

import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserRepository extends PageableRepository<User, UUID> {
    @NonNull
    CursoredPage<User> findAll(@NonNull CursoredPageable pageable);

    void updateRole(@io.micronaut.data.annotation.Id UUID id, lol.pbu.kaiju.model.UserRole role);
}
