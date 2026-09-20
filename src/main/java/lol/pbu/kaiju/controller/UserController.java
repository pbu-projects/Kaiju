package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.util.ControllerUtils;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/users")
public class UserController implements ControllerUtils {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Get
    public CursoredPage<User> getUsers(@Valid CursoredPageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Optional<User> getUser(@PathVariable UUID id) {
        return userRepository.findById(id);
    }

    @Post
    public User addUser(@Valid @Body User user) {
        return userRepository.save(user);
    }

    /**
     * Updates an existing user by its ID.
     * Prevents Mass Assignment by explicitly retaining the existing user's role and createdAt timestamp.
     * Throws 404 NOT_FOUND if the user does not exist.
     *
     * @param id   the ID of the user to update
     * @param user the updated user details
     * @return the updated user
     */
    @Put("/{id}")
    public User updateUser(@PathVariable UUID id, @Valid @Body User user) {
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
     * Throws 404 NOT_FOUND if the user does not exist.
     *
     * @param id the ID of the user to delete
     */
    @Delete("/{id}")
    public void deleteUser(@PathVariable UUID id) {
        checkExists(userRepository, id);
        userRepository.deleteById(id);
    }
}
