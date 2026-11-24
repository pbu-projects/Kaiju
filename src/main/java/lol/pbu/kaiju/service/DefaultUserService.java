package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.UserRole;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.AuthorizationService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class DefaultUserService implements UserService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultUserService.class);
    private static final String SQL_STATE_UNIQUE_VIOLATION = PSQLState.UNIQUE_VIOLATION.getState();
    private static final String USER_NOT_FOUND = "User not found";

    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;

    public DefaultUserService(UserRepository userRepository, AuthorizationService authorizationService) {
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public CursoredPage<User> getUsers(@NonNull CursoredPageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public User getCurrentUser(@NonNull UUID actorUserId) {
        return userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, USER_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public Optional<User> getUserById(@NonNull UUID id, @NonNull UUID actorUserId) {
        authorizationService.authorizeSelfOrUserManager(actorUserId, id);
        return userRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public Optional<User> getUserByEmail(@Nullable String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(canonicalizeEmail(email));
    }

    @Override
    @Transactional
    @NonNull
    public User createUser(@NonNull User user, @NonNull UUID actorUserId) {
        authorizationService.authorizeUserManager(actorUserId);
        String canonicalEmail = user.email() != null ? canonicalizeEmail(user.email()) : null;
        User toSave = new User(
                null,
                canonicalEmail,
                user.role() != null ? user.role() : UserRole.STANDARD_USER,
                user.createdAt() != null ? user.createdAt() : OffsetDateTime.now(ZoneOffset.UTC)
        );
        return userRepository.save(toSave);
    }

    @Override
    @Transactional
    @NonNull
    public User updateUser(@NonNull UUID id, @NonNull User user, @NonNull UUID actorUserId) {
        authorizationService.authorizeSelfOrUserManager(actorUserId, id);
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, USER_NOT_FOUND));

        String canonicalEmail = user.email() != null ? canonicalizeEmail(user.email()) : null;
        User safeUpdate = new User(
                id,
                canonicalEmail,
                existing.role(),
                existing.createdAt()
        );
        return userRepository.update(safeUpdate);
    }

    @Override
    @Transactional
    @NonNull
    public User updateRole(@NonNull UUID id, @NonNull UserRole role, @NonNull UUID actorUserId) {
        authorizationService.authorizeSystemAdmin(actorUserId);
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, USER_NOT_FOUND));
        userRepository.updateRole(id, role);
        return new User(existing.id(), existing.email(), role, existing.createdAt());
    }

    @Override
    @Transactional
    public void deleteUser(@NonNull UUID id, @NonNull UUID actorUserId) {
        authorizationService.authorizeSelfOrUserManager(actorUserId, id);
        long count = userRepository.removeById(id);
        if (count == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, USER_NOT_FOUND);
        }
    }

    @Override
    @NonNull
    public User provisionOrGetUser(@NonNull String email) {
        return provisionOrGetUser(email, UserRole.STANDARD_USER);
    }

    @Override
    @NonNull
    public User provisionOrGetUser(@NonNull String email, @NonNull UserRole initialRole) {
        String canonicalEmail = canonicalizeEmail(email);
        return userRepository.findByEmail(canonicalEmail).orElseGet(() -> {
            try {
                User newUser = new User(null, canonicalEmail, initialRole, OffsetDateTime.now(ZoneOffset.UTC));
                return userRepository.save(newUser);
            } catch (Exception ex) {
                if (isUniqueConstraintViolation(ex)) {
                    LOG.info("Concurrent user insertion detected for email: {}. Retrieving existing record.", canonicalEmail);
                    return userRepository.findByEmail(canonicalEmail)
                            .orElseThrow(() -> new IllegalStateException("User record could not be found after unique violation on email: " + canonicalEmail, ex));
                }
                LOG.error("Database error occurred during user provisioning for email: {}", canonicalEmail, ex);
                if (ex instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Database error during user provisioning", ex);
            }
        });
    }

    private String canonicalizeEmail(@NonNull String email) {
        return email.toLowerCase(Locale.ROOT).trim();
    }

    private boolean isUniqueConstraintViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && SQL_STATE_UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
