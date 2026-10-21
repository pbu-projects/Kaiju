package lol.pbu.kaiju.security;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.RolesFinder;
import io.micronaut.security.token.config.TokenConfiguration;
import io.micronaut.security.token.jwt.validator.DefaultJwtAuthenticationFactory;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.service.UserService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Singleton
@Replaces(DefaultJwtAuthenticationFactory.class)
public class KaijuJwtAuthenticationFactory extends DefaultJwtAuthenticationFactory {

    private static final Logger LOG = LoggerFactory.getLogger(KaijuJwtAuthenticationFactory.class);

    private final UserService userService;

    public KaijuJwtAuthenticationFactory(TokenConfiguration tokenConfiguration,
                                         RolesFinder rolesFinder,
                                         UserService userService) {
        super(tokenConfiguration, rolesFinder);
        this.userService = userService;
    }

    @Override
    @NonNull
    public Optional<Authentication> createAuthentication(@NonNull JWT token) {
        try {
            JWTClaimsSet claimsSet = token.getJWTClaimsSet();
            if (claimsSet == null) {
                return Optional.empty();
            }
            Map<String, Object> claims = claimsSet.getClaims();
            Object emailObj = claims.get("email");
            if (!(emailObj instanceof String email) || email.isBlank()) {
                LOG.warn("Rejecting JWT: missing or invalid email claim");
                return Optional.empty();
            }

            Object emailVerifiedObj = claims.get("email_verified");
            if (emailVerifiedObj != null) {
                if (Boolean.FALSE.equals(emailVerifiedObj)
                        || (emailVerifiedObj instanceof String s && "false".equalsIgnoreCase(s.trim()))) {
                    LOG.warn("Rejecting JWT: unverified email claim");
                    return Optional.empty();
                }
            }

            try {
                String canonicalEmail = email.toLowerCase(Locale.ROOT).trim();
                User user = userService.provisionOrGetUser(canonicalEmail);
                List<String> roles = new ArrayList<>();
                roles.add(user.role().name());
                user.role().getPermissions().stream()
                        .map(Permission::getClaim)
                        .forEach(roles::add);
                return Optional.of(Authentication.build(
                        user.id().toString(),
                        roles,
                        claims
                ));
            } catch (Exception e) {
                LOG.error("Failed to authenticate user due to database or service error", e);
                return Optional.empty();
            }
        } catch (ParseException e) {
            LOG.error("Failed to parse JWT claims when creating authentication", e);
            return Optional.empty();
        }
    }
}
