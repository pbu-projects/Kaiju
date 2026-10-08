package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.UserRole;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.Permission;
import lol.pbu.kaiju.util.ControllerUtils;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.FORBIDDEN;
import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.security.Permission.SYSTEM_USER_MANAGE_CLAIM;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/users")
public class UserController implements ControllerUtils {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Get
    @Secured(SYSTEM_USER_MANAGE_CLAIM)
    public CursoredPage<User> getUsers(@Valid CursoredPageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Optional<User> getUser(@PathVariable UUID id, @Nullable Principal principal) {
        if (principal != null) {
            verifySelfOrAdmin(id, principal);
        }
        return userRepository.findById(id);
    }

    public Optional<User> getUser(UUID id) {
        return getUser(id, null);
    }

    @Post
    public User addUser(@Valid @Body User user, @Nullable Principal principal) {
        UserRole targetRole = UserRole.STANDARD_USER;
        if (user.role() != null && user.role() != UserRole.STANDARD_USER) {
            if (principal != null) {
                verifyAdmin(principal);
            }
            targetRole = user.role();
        }
        User safeUser = new User(
                null,
                user.email(),
                targetRole,
                user.createdAt() != null ? user.createdAt() : OffsetDateTime.now(ZoneOffset.UTC)
        );
        return userRepository.save(safeUser);
    }

    public User addUser(User user) {
        return addUser(user, null);
    }

    /**
     * Updates an existing user by its ID.
     * Prevents Mass Assignment by explicitly retaining the existing user's role and createdAt timestamp.
     * Enforces horizontal authorization (IDOR): caller must match id or hold SYSTEM_USER_MANAGE.
     * Throws 403 FORBIDDEN if the user attempts to modify another account without system manage privileges.
     * Throws 404 NOT_FOUND if the user does not exist.
     *
     * @param id        the ID of the user to update
     * @param user      the updated user details
     * @param principal the authenticated principal
     * @return the updated user
     */
    @Put("/{id}")
    public User updateUser(@PathVariable UUID id, @Valid @Body User user, Principal principal) {
        verifySelfOrAdmin(id, principal);

        User existingUser = userRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "User not found"));

        // Copy over allowed fields, but retain strictly controlled fields
        User safeUpdate = new User(
                id,
                user.email(),
                existingUser.role(), // Ignore the role from the request
                existingUser.createdAt()
        );
        return userRepository.update(safeUpdate);
    }

    /**
     * Deletes a user by its ID after validating that it exists.
     * Enforces horizontal authorization (IDOR): caller must match id or hold SYSTEM_USER_MANAGE.
     * Throws 403 FORBIDDEN if the user attempts to delete another account without system manage privileges.
     * Throws 404 NOT_FOUND if the user does not exist.
     *
     * @param id        the ID of the user to delete
     * @param principal the authenticated principal
     */
    @Delete("/{id}")
    public void deleteUser(@PathVariable UUID id, Principal principal) {
        verifySelfOrAdmin(id, principal);
        checkExists(userRepository, id);
        userRepository.deleteById(id);
    }

    private void verifySelfOrAdmin(UUID targetUserId, Principal principal) {
        UUID callerId = UUID.fromString(principal.getName());
        boolean isSelf = callerId.equals(targetUserId);
        if (isSelf) {
            return;
        }
        boolean hasAdminManage = userRepository.findById(callerId)
                .map(u -> u.role().hasPermission(Permission.SYSTEM_USER_MANAGE) || u.role().hasPermission(Permission.SYSTEM_ADMIN))
                .orElse(false);
        if (!hasAdminManage) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: You cannot modify or delete another user's account");
        }
    }

    private void verifyAdmin(Principal principal) {
        UUID callerId = UUID.fromString(principal.getName());
        boolean hasAdmin = userRepository.findById(callerId)
                .map(u -> u.role().hasPermission(Permission.SYSTEM_USER_MANAGE) || u.role().hasPermission(Permission.SYSTEM_ADMIN))
                .orElse(false);
        if (!hasAdmin) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: Only administrators can assign elevated user roles");
        }
    }
}
