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
import lol.pbu.kaiju.model.UserRole;
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
    private final IdpRoleMapper idpRoleMapper;

    public KaijuJwtAuthenticationFactory(@NonNull TokenConfiguration tokenConfiguration,
                                         @NonNull RolesFinder rolesFinder,
                                         @NonNull UserService userService,
                                         @NonNull IdpRoleMapper idpRoleMapper) {
        super(tokenConfiguration, rolesFinder);
        this.userService = userService;
        this.idpRoleMapper = idpRoleMapper;
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
            boolean isVerified = Boolean.TRUE.equals(emailVerifiedObj)
                    || (emailVerifiedObj instanceof String s && "true".equalsIgnoreCase(s.trim()));
            if (!isVerified) {
                LOG.warn("Rejecting JWT: email is not verified or verification claim is missing for {}", email);
                return Optional.empty();
            }

            return authenticateUser(email, claims);
        } catch (ParseException e) {
            LOG.error("Failed to parse JWT claims when creating authentication", e);
            return Optional.empty();
        }
    }

    @NonNull
    private Optional<Authentication> authenticateUser(@NonNull String email, @NonNull Map<String, Object> claims) {
        try {
            String canonicalEmail = email.toLowerCase(Locale.ROOT).trim();
            UserRole initialRole = idpRoleMapper.resolveRoleFromClaims(claims);
            User user = userService.provisionOrGetUser(canonicalEmail, initialRole);
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
    }
}
