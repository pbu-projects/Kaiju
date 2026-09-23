package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Join;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.OrganizationAuditLog;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface OrganizationAuditLogRepository extends PageableRepository<OrganizationAuditLog, UUID> {
    @NonNull
    CursoredPage<OrganizationAuditLog> findAll(@NonNull CursoredPageable pageable);

    @NonNull
    @Join("organization")
    @Join("actor")
    Optional<OrganizationAuditLog> findById(@NonNull UUID id);
}
