package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.User;
import lol.pbu.kaiju.core.repository.UserRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/users")
public class UserController {

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
     * Updates an existing user by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the user does not exist.
     * Refer to the sister method {@link #updateUserNoLook(UUID, User)} to update without validation.
     *
     * @param id   the ID of the user to update
     * @param user the updated user details
     * @return the updated user
     */
    @Put("/{id}")
    public User updateUser(@PathVariable UUID id, @Valid @Body User user) {
        if (!userRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return userRepository.update(user.withId(id));
    }

    /**
     * Updates a user by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #updateUser(UUID, User)} to update with existence validation.
     *
     * @param id   the ID of the user to update
     * @param user the updated user details
     * @return the updated user
     */
    @Put("/{id}/no-look")
    public User updateUserNoLook(@PathVariable UUID id, @Valid @Body User user) {
        return userRepository.update(user.withId(id));
    }

    /**
     * Deletes a user by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the user does not exist.
     * Refer to the sister method {@link #deleteUserNoLook(UUID)} to delete without validation.
     *
     * @param id the ID of the user to delete
     */
    @Delete("/{id}")
    public void deleteUser(@PathVariable UUID id) {
        if (!userRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        userRepository.deleteById(id);
    }

    /**
     * Deletes a user by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #deleteUser(UUID)} to delete with existence validation.
     *
     * @param id the ID of the user to delete
     */
    @Delete("/{id}/no-look")
    public void deleteUserNoLook(@PathVariable UUID id) {
        userRepository.deleteById(id);
    }
}
