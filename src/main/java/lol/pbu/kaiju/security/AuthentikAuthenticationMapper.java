package lol.pbu.kaiju.security;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.oauth2.endpoint.authorization.state.State;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdAuthenticationMapper;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.UserRole;
import lol.pbu.kaiju.repository.UserRepository;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Named("authentik")
@Singleton
@ExecuteOn(TaskExecutors.IO)
public class AuthentikAuthenticationMapper implements OpenIdAuthenticationMapper {

    private final UserRepository userRepository;

    public AuthentikAuthenticationMapper(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @NonNull
    public org.reactivestreams.Publisher<AuthenticationResponse> createAuthenticationResponse(
            @NonNull String providerName,
            @NonNull OpenIdTokenResponse tokenResponse,
            @NonNull OpenIdClaims openIdClaims,
            @Nullable State state) {
        return reactor.core.publisher.Mono.fromCallable(() -> {
            String email = openIdClaims.getEmail();
            if (email == null || email.isBlank()) {
                return AuthenticationResponse.failure("No email present in OpenID claims");
            }

            // Just-In-Time Provisioning with race condition fix
            Optional<User> optionalUser = userRepository.findByEmail(email);
            User user;
            if (optionalUser.isPresent()) {
                user = optionalUser.get();
            } else {
                try {
                    // Attempt to create the user as a STANDARD_USER
                    User newUser = new User(null, email, UserRole.STANDARD_USER, OffsetDateTime.now(java.time.ZoneId.systemDefault()));
                    user = userRepository.save(newUser);
                } catch (io.micronaut.data.exceptions.DataAccessException e) {
                    // If another thread just created them, fetch again
                    user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("Failed to fetch user after constraint violation", e));
                }
            }

            // Map database role to Micronaut Security Context
            // Using the user's UUID as the principal name is best practice since emails can change
            return AuthenticationResponse.success(
                    user.id().toString(),
                    Collections.singletonList(user.role().name()),
                    Map.of("email", user.email())
            );
        });
    }
}
