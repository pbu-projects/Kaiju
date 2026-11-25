package lol.pbu.kaiju.security;

import jakarta.inject.Singleton;
import lol.pbu.kaiju.model.UserRole;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps login provider group names to Kaiju user roles.
 *
 * <p>When a user belongs to more than one mapped group, the role with the most access wins:
 * GLOBAL_ADMIN &gt; REGION_DIRECTOR &gt; REGION_AGENT &gt; STANDARD_USER.</p>
 */
@Singleton
public class IdentityProviderRoleMapper {

    public static final String GROUP_GLOBAL_ADMINS = "Kaiju Global Admins";
    public static final String GROUP_REGION_DIRECTORS = "Kaiju Region Directors";
    public static final String GROUP_REGION_AGENTS = "Kaiju Region Agents";
    public static final String CLAIMS_GROUPS_KEY = "groups";

    /**
     * Resolves the user role from a collection of login provider group names.
     *
     * @param groups collection of group names from the login provider
     * @return the matching {@link UserRole} with the most access, or {@link UserRole#STANDARD_USER} by default
     */
    @NonNull
    public UserRole resolveRoleFromGroups(@Nullable Collection<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return UserRole.STANDARD_USER;
        }

        if (groups.contains(GROUP_GLOBAL_ADMINS)) {
            return UserRole.GLOBAL_ADMIN;
        }
        if (groups.contains(GROUP_REGION_DIRECTORS)) {
            return UserRole.REGION_DIRECTOR;
        }
        if (groups.contains(GROUP_REGION_AGENTS)) {
            return UserRole.REGION_AGENT;
        }

        return UserRole.STANDARD_USER;
    }

    /**
     * Reads group names from a login claims map and picks the matching user role.
     *
     * @param claims incoming login claims map
     * @return the matching {@link UserRole} with the most access, or {@link UserRole#STANDARD_USER} by default
     */
    @NonNull
    public UserRole resolveRoleFromClaims(@Nullable Map<String, Object> claims) {
        if (claims == null || claims.isEmpty()) {
            return UserRole.STANDARD_USER;
        }

        return resolveRoleFromGroupObject(claims.get(CLAIMS_GROUPS_KEY));
    }

    /**
     * Resolves the user role from a raw groups claim, which may be a
     * collection, an array, or a single string.
     *
     * @param groupsObj raw groups value from login claims
     * @return the matching {@link UserRole} with the most access, or {@link UserRole#STANDARD_USER} by default
     */
    @NonNull
    public UserRole resolveRoleFromGroupObject(@Nullable Object groupsObj) {
        List<String> groups = extractGroupStrings(groupsObj);
        return resolveRoleFromGroups(groups);
    }

    @NonNull
    private List<String> extractGroupStrings(@Nullable Object groupsObj) {
        if (groupsObj == null) {
            return Collections.emptyList();
        }

        if (groupsObj instanceof Object[] array) {
            return collectStrings(Arrays.asList(array));
        }

        if (groupsObj instanceof Collection<?> collection) {
            return collectStrings(collection);
        }

        if (groupsObj instanceof String s) {
            return Collections.singletonList(s);
        }

        return Collections.emptyList();
    }

    @NonNull
    private List<String> collectStrings(@NonNull Collection<?> collection) {
        return collection.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
