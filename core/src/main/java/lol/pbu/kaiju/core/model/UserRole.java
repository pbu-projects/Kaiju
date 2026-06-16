package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum UserRole {
    SUPER_ADMIN, MODERATOR, ORGANIZATION_LEADER, VOLUNTEER
}
