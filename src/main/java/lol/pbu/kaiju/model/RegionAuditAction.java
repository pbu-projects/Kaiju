package lol.pbu.kaiju.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum RegionAuditAction {
    CREATE,
    UPDATE,
    DELETE,
    BOUNDARY_INGEST
}
