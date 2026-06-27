package lol.pbu.kaiju.core.repository;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.annotation.Join;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.core.domain.OrganizationAuditLog;

import java.util.Optional;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface OrganizationAuditLogRepository extends PageableRepository<OrganizationAuditLog, UUID> {
    @NonNull
    CursoredPage<OrganizationAuditLog> findAll(@NonNull CursoredPageable pageable);

    @NonNull
    @Join("organization")
    @Join("actor")
    Optional<OrganizationAuditLog> findById(@NonNull UUID id);
}
