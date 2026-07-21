package lol.pbu.kaiju.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum AuditAction {
    CREATED,
    APPROVED,
    REJECTED,
    EDITED
}
