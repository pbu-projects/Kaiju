package lol.pbu.kaiju.repository;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.annotation.Join;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.domain.ProjectAuditLog;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.data.model.query.builder.sql.Dialect.POSTGRES;

@JdbcRepository(dialect = POSTGRES)
public interface ProjectAuditLogRepository extends PageableRepository<ProjectAuditLog, UUID> {

    @NonNull
    @Join("project")
    @Join("project.organization")
    @Join("actor")
    CursoredPage<ProjectAuditLog> findAll(@NonNull CursoredPageable pageable);

    @NonNull
    @Join("project")
    @Join("project.organization")
    @Join("actor")
    Optional<ProjectAuditLog> findById(@NonNull UUID id);
}
