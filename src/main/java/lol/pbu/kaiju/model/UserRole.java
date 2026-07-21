package lol.pbu.kaiju.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum UserRole {
    GLOBAL_ADMIN,
    REGION_DIRECTOR,
    REGION_AGENT,
    STANDARD_USER
}
