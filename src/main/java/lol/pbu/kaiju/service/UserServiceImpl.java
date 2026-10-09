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
import org.jspecify.annotations.NonNull;
import org.postgresql.util.PSQLState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class UserServiceImpl implements UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserServiceImpl.class);
    private static final String SQL_STATE_UNIQUE_VIOLATION = PSQLState.UNIQUE_VIOLATION.getState();
    private static final String USER_NOT_FOUND = "User not found";

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
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
    public Optional<User> getUserById(@NonNull UUID id) {
        return userRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public Optional<User> getUserByEmail(@NonNull String email) {
        return userRepository.findByEmail(email);
    }

    @Override
    @Transactional
    @NonNull
    public User createUser(@NonNull User user) {
        User toSave = new User(
                null,
                user.email(),
                user.role() != null ? user.role() : UserRole.STANDARD_USER,
                user.createdAt() != null ? user.createdAt() : OffsetDateTime.now(ZoneOffset.UTC)
        );
        return userRepository.save(toSave);
    }

    @Override
    @Transactional
    @NonNull
    public User updateUser(@NonNull UUID id, @NonNull User user) {
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, USER_NOT_FOUND));

        User safeUpdate = new User(
                id,
                user.email(),
                existing.role(),
                existing.createdAt()
        );
        return userRepository.update(safeUpdate);
    }

    @Override
    @Transactional
    public void updateRole(@NonNull UUID id, @NonNull UserRole role) {
        userRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, USER_NOT_FOUND));
        userRepository.updateRole(id, role);
    }

    @Override
    @Transactional
    public void deleteUser(@NonNull UUID id) {
        long count = userRepository.removeById(id);
        if (count == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, USER_NOT_FOUND);
        }
    }

    @Override
    @NonNull
    public User provisionOrGetUser(@NonNull String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            try {
                User newUser = new User(null, email, UserRole.STANDARD_USER, OffsetDateTime.now(ZoneOffset.UTC));
                return userRepository.save(newUser);
            } catch (Exception ex) {
                if (isUniqueConstraintViolation(ex)) {
                    LOG.info("Concurrent user insertion detected for email: {}. Retrieving existing record.", email);
                    return userRepository.findByEmail(email)
                            .orElseThrow(() -> new IllegalStateException("User record could not be found after unique violation on email: " + email, ex));
                }
                LOG.error("Database error occurred during user provisioning for email: {}", email, ex);
                if (ex instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Database error during user provisioning", ex);
            }
        });
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
