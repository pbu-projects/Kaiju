package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum AuditAction {
    CREATED, APPROVED, REJECTED, EDITED
}
