package lol.pbu.kaiju.security;

import jakarta.inject.Singleton;
import lol.pbu.kaiju.model.UserRole;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Translates upstream Identity Provider (IdP) group claims into internal application roles.
 *
 * <p>Resolves incoming groups to the highest privilege tier matching the mapped groups.
 * If multiple groups match, the precedence is:
 * GLOBAL_ADMIN &gt; REGION_DIRECTOR &gt; REGION_AGENT &gt; STANDARD_USER.</p>
 */
@Singleton
public class IdpRoleMapper {

    public static final String GROUP_GLOBAL_ADMINS = "Kaiju Global Admins";
    public static final String GROUP_REGION_DIRECTORS = "Kaiju Region Directors";
    public static final String GROUP_REGION_AGENTS = "Kaiju Region Agents";
    public static final String CLAIMS_GROUPS_KEY = "groups";

    /**
     * Resolves the user role based on a collection of IdP groups.
     *
     * @param groups collection of group names assigned in the upstream IdP
     * @return the highest matching {@link UserRole}, or {@link UserRole#STANDARD_USER} by default
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
     * Extracts group claims from an IdP claims map and resolves the corresponding user role.
     *
     * @param claims incoming JWT or OpenID claims map
     * @return the highest matching {@link UserRole}, or {@link UserRole#STANDARD_USER} by default
     */
    @NonNull
    public UserRole resolveRoleFromClaims(@Nullable Map<String, Object> claims) {
        if (claims == null || claims.isEmpty()) {
            return UserRole.STANDARD_USER;
        }

        return resolveRoleFromGroupObject(claims.get(CLAIMS_GROUPS_KEY));
    }

    /**
     * Resolves the user role from an incoming groups claim object, which may be a
     * collection, an array, or a single string.
     *
     * @param groupsObj raw groups object from IdP claims
     * @return the highest matching {@link UserRole}, or {@link UserRole#STANDARD_USER} by default
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
        List<String> list = new ArrayList<>(collection.size());
        for (Object item : collection) {
            if (item instanceof String s) {
                list.add(s);
            } else if (item != null) {
                list.add(item.toString());
            }
        }
        return list;
    }
}
