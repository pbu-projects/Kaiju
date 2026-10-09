package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.dto.CreateUserCommand;
import lol.pbu.kaiju.dto.UpdateUserCommand;
import lol.pbu.kaiju.model.UserRole;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.Permission;
import lol.pbu.kaiju.service.UserService;
import lol.pbu.kaiju.util.PageableUtils;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.FORBIDDEN;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.security.Permission.SYSTEM_USER_MANAGE_CLAIM;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/users")
public class UserController {

    public static final String DEFAULT_SORT_FIELD = "email";

    private final UserService userService;
    private final UserRepository userRepository;

    public UserController(UserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
    }

    @Get
    @Secured(SYSTEM_USER_MANAGE_CLAIM)
    public CursoredPage<User> getUsers(@Nullable @Valid CursoredPageable pageable) {
        return userService.getUsers(PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    @Get("/{id}")
    public Optional<User> getUser(@PathVariable UUID id, Principal principal) {
        verifySelfOrAdmin(id, principal);
        return userService.getUserById(id);
    }

    @Post
    public User addUser(@Valid @Body CreateUserCommand command, Principal principal) {
        UserRole targetRole = UserRole.STANDARD_USER;
        if (command.role() != null && command.role() != UserRole.STANDARD_USER) {
            verifyAdmin(principal);
            targetRole = command.role();
        }
        User safeUser = new User(
                null,
                command.email(),
                targetRole,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return userService.createUser(safeUser);
    }

    /**
     * Updates an existing user by its ID.
     * Prevents Mass Assignment by explicitly retaining the existing user's role and createdAt timestamp.
     * Enforces horizontal authorization (IDOR): caller must match id or hold SYSTEM_USER_MANAGE.
     * Throws 403 FORBIDDEN if the user attempts to modify another account without system manage privileges.
     * Throws 404 NOT_FOUND if the user does not exist.
     *
     * @param id        the ID of the user to update
     * @param command   the updated user details
     * @param principal the authenticated principal
     * @return the updated user
     */
    @Put("/{id}")
    public User updateUser(@PathVariable UUID id, @Valid @Body UpdateUserCommand command, Principal principal) {
        verifySelfOrAdmin(id, principal);
        User candidate = new User(
                id,
                command.email(),
                null,
                null
        );
        return userService.updateUser(id, candidate);
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
        userService.deleteUser(id);
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
