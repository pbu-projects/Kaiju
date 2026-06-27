package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum OrganizationUserRole {
    ORG_ADMIN,
    ORG_MANAGER,
    ORG_MEMBER
}
