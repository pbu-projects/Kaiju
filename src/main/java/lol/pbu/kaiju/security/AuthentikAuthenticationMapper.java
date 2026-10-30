package lol.pbu.kaiju.security;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.oauth2.endpoint.authorization.state.State;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdAuthenticationMapper;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.UserRole;
import lol.pbu.kaiju.service.UserService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Named("authentik")
@Singleton
@ExecuteOn(TaskExecutors.VIRTUAL)
public class AuthentikAuthenticationMapper implements OpenIdAuthenticationMapper {

    private final UserService userService;
    private final IdpRoleMapper idpRoleMapper;

    public AuthentikAuthenticationMapper(@NonNull UserService userService, @NonNull IdpRoleMapper idpRoleMapper) {
        this.userService = userService;
        this.idpRoleMapper = idpRoleMapper;
    }

    @Override
    @NonNull
    public Publisher<AuthenticationResponse> createAuthenticationResponse(
            @NonNull String providerName,
            @NonNull OpenIdTokenResponse tokenResponse,
            @NonNull OpenIdClaims openIdClaims,
            @Nullable State state) {
        String email = openIdClaims.getEmail();
        if (email == null || email.isBlank()) {
            return Publishers.just(AuthenticationResponse.failure("No email present in OpenID claims"));
        }

        Object emailVerifiedObj = openIdClaims.get("email_verified");
        boolean isVerified = Boolean.TRUE.equals(emailVerifiedObj)
                || (emailVerifiedObj instanceof String s && "true".equalsIgnoreCase(s.trim()));
        if (!isVerified) {
            return Publishers.just(AuthenticationResponse.failure("Email is not verified in OpenID claims"));
        }

        String canonicalEmail = email.toLowerCase(Locale.ROOT).trim();
        Object groupsObj = openIdClaims.get(IdpRoleMapper.CLAIMS_GROUPS_KEY);
        UserRole initialRole = idpRoleMapper.resolveRoleFromGroupObject(groupsObj);
        User user = userService.provisionOrGetUser(canonicalEmail, initialRole);

        // Map database role to Micronaut Security Context
        // Using the user's UUID as the principal name is best practice since emails can change
        List<String> roles = new ArrayList<>();
        roles.add(user.role().name());
        user.role().getPermissions().stream()
                .map(Permission::getClaim)
                .forEach(roles::add);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("email", user.email());
        attributes.put("email_verified", true);
        attributes.put(OPENID_TOKEN_KEY, tokenResponse.getIdToken());
        attributes.values().removeIf(value -> value == null || (value instanceof String s && s.isBlank()));

        AuthenticationResponse response = AuthenticationResponse.success(
                user.id().toString(),
                roles,
                attributes
        );

        return Publishers.just(response);
    }
}
