package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.OrganizationUser;
import lol.pbu.kaiju.domain.OrganizationUserId;
import lol.pbu.kaiju.model.OrganizationUserRole;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface OrganizationUserRepository extends PageableRepository<OrganizationUser, OrganizationUserId> {
    @NonNull
    CursoredPage<OrganizationUser> findAll(@NonNull CursoredPageable pageable);

    @Query("UPDATE organization_users SET role = :role WHERE user_id = :userId AND organization_id = :organizationId")
    long updateRole(@NonNull UUID userId, @NonNull UUID organizationId, @NonNull OrganizationUserRole role);

    long removeById(@NonNull OrganizationUserId id);
}
