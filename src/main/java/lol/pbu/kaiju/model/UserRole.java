package lol.pbu.kaiju.model;

import io.micronaut.serde.annotation.Serdeable;
import lol.pbu.kaiju.security.Permission;

import java.util.Set;

@Serdeable
public enum UserRole {
    STANDARD_USER(Set.of(
            Permission.PROJECT_CREATE,
            Permission.PROJECT_READ,
            Permission.PROJECT_UPDATE,
            Permission.PROJECT_DELETE,
            Permission.ORG_READ
    )),
    REGION_AGENT(Set.of(
            Permission.PROJECT_CREATE,
            Permission.PROJECT_READ,
            Permission.PROJECT_UPDATE,
            Permission.PROJECT_DELETE,
            Permission.PROJECT_APPROVE,
            Permission.ORG_READ
    )),
    REGION_DIRECTOR(Set.of(
            Permission.PROJECT_CREATE,
            Permission.PROJECT_READ,
            Permission.PROJECT_UPDATE,
            Permission.PROJECT_DELETE,
            Permission.PROJECT_APPROVE,
            Permission.ORG_READ,
            Permission.REGION_MANAGE
    )),
    GLOBAL_ADMIN(Set.of(
            Permission.PROJECT_CREATE,
            Permission.PROJECT_READ,
            Permission.PROJECT_UPDATE,
            Permission.PROJECT_DELETE,
            Permission.PROJECT_APPROVE,
            Permission.ORG_READ,
            Permission.ORG_EDIT,
            Permission.ORG_MANAGE_USERS,
            Permission.REGION_MANAGE,
            Permission.SYSTEM_USER_MANAGE,
            Permission.SYSTEM_ADMIN
    ));

    private final Set<Permission> permissions;

    UserRole(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission);
    }
}
