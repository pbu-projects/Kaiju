package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.User;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface UserRepository extends PageableRepository<User, UUID> {
    @NonNull
    CursoredPage<User> findAll(@NonNull CursoredPageable pageable);

    void updateRole(@Id UUID id, String role);

    Optional<User> findByEmail(String email);
}
