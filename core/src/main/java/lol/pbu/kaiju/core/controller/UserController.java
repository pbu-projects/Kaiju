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

    @Put("/{id}")
    public User updateUser(@PathVariable UUID id, @Valid @Body User user) {
        if (!userRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return userRepository.update(user.withId(id));
    }

    @Delete("/{id}")
    public void deleteUser(@PathVariable UUID id) {
        userRepository.deleteById(id);
    }
}
