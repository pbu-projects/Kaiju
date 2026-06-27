package lol.pbu.kaiju.core.repository;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.core.domain.OrganizationUser;
import lol.pbu.kaiju.core.domain.OrganizationUserId;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface OrganizationUserRepository extends PageableRepository<OrganizationUser, OrganizationUserId> {
    @NonNull
    CursoredPage<OrganizationUser> findAll(@NonNull CursoredPageable pageable);
}
