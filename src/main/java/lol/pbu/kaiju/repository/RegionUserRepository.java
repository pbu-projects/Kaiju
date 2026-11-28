package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.RegionUser;
import lol.pbu.kaiju.domain.RegionUserId;
import lol.pbu.kaiju.model.RegionUserRole;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface RegionUserRepository extends PageableRepository<RegionUser, RegionUserId> {
    @NonNull
    CursoredPage<RegionUser> findAll(@NonNull CursoredPageable pageable);

    @Query("UPDATE region_users SET role = :role WHERE user_id = :userId AND region_id = :regionId")
    long updateRole(@NonNull UUID userId, @NonNull UUID regionId, @NonNull RegionUserRole role);

    long removeById(@NonNull RegionUserId id);
}
