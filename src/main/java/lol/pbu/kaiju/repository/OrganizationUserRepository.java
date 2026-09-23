package lol.pbu.kaiju.repository;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.OrganizationUser;
import lol.pbu.kaiju.domain.OrganizationUserId;
import org.jspecify.annotations.NonNull;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface OrganizationUserRepository extends PageableRepository<OrganizationUser, OrganizationUserId> {
    @NonNull
    CursoredPage<OrganizationUser> findAll(@NonNull CursoredPageable pageable);
}
