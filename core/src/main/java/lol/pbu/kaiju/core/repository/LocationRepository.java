package lol.pbu.kaiju.core.repository;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import lol.pbu.kaiju.core.domain.Location;

import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface LocationRepository extends PageableRepository<Location, UUID>{
}