package lol.pbu.kaiju.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum ProjectStatus {
    DRAFT,
    PENDING,
    PENDING_UPDATE,
    ACTIVE,
    FLAGGED,
    REJECTED
}