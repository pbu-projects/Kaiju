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
import lol.pbu.kaiju.service.UserService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import java.util.Map;

@Named("authentik")
@Singleton
@ExecuteOn(TaskExecutors.VIRTUAL)
public class AuthentikAuthenticationMapper implements OpenIdAuthenticationMapper {

    private final UserService userService;

    public AuthentikAuthenticationMapper(UserService userService) {
        this.userService = userService;
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

        User user = userService.provisionOrGetUser(email);

        // Map database role to Micronaut Security Context
        // Using the user's UUID as the principal name is best practice since emails can change
        AuthenticationResponse response = AuthenticationResponse.success(
                user.id().toString(),
                user.role().getPermissions().stream().map(Permission::getClaim).toList(),
                Map.of("email", user.email())
        );

        return Publishers.just(response);
    }
}
