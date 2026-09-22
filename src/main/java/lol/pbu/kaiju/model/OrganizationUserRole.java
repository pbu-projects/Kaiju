package lol.pbu.kaiju.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum OrganizationUserRole {
    ORG_MEMBER,
    ORG_MANAGER,
    ORG_ADMIN
}
