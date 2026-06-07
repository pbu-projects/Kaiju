package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum ProjectStatus {
    DRAFT,
    PENDING,
    ACTIVE,
    FLAGGED,
    REJECTED
}