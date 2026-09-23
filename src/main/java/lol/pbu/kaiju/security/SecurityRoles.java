package lol.pbu.kaiju.security;

import io.micronaut.security.rules.SecurityRule;


/**
 * Security role constants used for @Secured annotations.
 * See {@code docs/06-security-matrix.adoc} for the full permissions matrix.
 */
public final class SecurityRoles {
    private SecurityRoles() {}

    public static final String IS_AUTHENTICATED = SecurityRule.IS_AUTHENTICATED;
    public static final String IS_ANONYMOUS = SecurityRule.IS_ANONYMOUS;

    public static final String GLOBAL_ADMIN = "GLOBAL_ADMIN";
    public static final String REGION_DIRECTOR = "REGION_DIRECTOR";
    public static final String REGION_AGENT = "REGION_AGENT";
    public static final String STANDARD_USER = "STANDARD_USER";

    public static final String ORG_ADMIN = "ORG_ADMIN";
    public static final String ORG_MANAGER = "ORG_MANAGER";
    public static final String ORG_MEMBER = "ORG_MEMBER";

    public static final String PROJECT_SPONSOR = "PROJECT_SPONSOR";
}
