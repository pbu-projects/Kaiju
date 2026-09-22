package lol.pbu.kaiju.security;

public enum Permission {
    PROJECT_CREATE(Permission.PROJECT_CREATE_CLAIM),
    PROJECT_READ(Permission.PROJECT_READ_CLAIM),
    PROJECT_UPDATE(Permission.PROJECT_UPDATE_CLAIM),
    PROJECT_DELETE(Permission.PROJECT_DELETE_CLAIM),
    PROJECT_APPROVE(Permission.PROJECT_APPROVE_CLAIM),
    ORG_READ(Permission.ORG_READ_CLAIM),
    ORG_EDIT(Permission.ORG_EDIT_CLAIM),
    ORG_MANAGE_USERS(Permission.ORG_MANAGE_USERS_CLAIM),
    REGION_MANAGE(Permission.REGION_MANAGE_CLAIM),
    SYSTEM_USER_MANAGE(Permission.SYSTEM_USER_MANAGE_CLAIM),
    SYSTEM_ADMIN(Permission.SYSTEM_ADMIN_CLAIM);

    public static final String PROJECT_CREATE_CLAIM = "project:create";
    public static final String PROJECT_READ_CLAIM = "project:read";
    public static final String PROJECT_UPDATE_CLAIM = "project:update";
    public static final String PROJECT_DELETE_CLAIM = "project:delete";
    public static final String PROJECT_APPROVE_CLAIM = "project:approve";
    public static final String ORG_READ_CLAIM = "org:read";
    public static final String ORG_EDIT_CLAIM = "org:edit";
    public static final String ORG_MANAGE_USERS_CLAIM = "org:manage_users";
    public static final String REGION_MANAGE_CLAIM = "region:manage";
    public static final String SYSTEM_USER_MANAGE_CLAIM = "system:user:manage";
    public static final String SYSTEM_ADMIN_CLAIM = "system:admin";

    private final String claim;

    Permission(String claim) {
        this.claim = claim;
    }

    public String getClaim() {
        return claim;
    }
}
