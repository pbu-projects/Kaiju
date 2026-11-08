package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Join;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.AdministrativeRegionAuditLog;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface AdministrativeRegionAuditLogRepository
        extends PageableRepository<AdministrativeRegionAuditLog, UUID> {

    @NonNull
    @Join("actor")
    CursoredPage<AdministrativeRegionAuditLog> findAll(@NonNull CursoredPageable pageable);

    @NonNull
    @Join("actor")
    CursoredPage<AdministrativeRegionAuditLog> findByRegionId(@NonNull UUID regionId, @NonNull CursoredPageable pageable);

    @NonNull
    @Join("actor")
    Optional<AdministrativeRegionAuditLog> findById(@NonNull UUID id);

    long removeById(@NonNull UUID id);
}
