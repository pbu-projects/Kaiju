package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.User;
import lol.pbu.kaiju.core.model.UserRequest;
import lol.pbu.kaiju.core.model.UserResponse;
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
    public Page<UserResponse> list(@Valid Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toResponse);
    }

    @Get("/{id}")
    public Optional<UserResponse> get(@PathVariable UUID id) {
        return userRepository.findById(id).map(this::toResponse);
    }

    @Post
    @Status(HttpStatus.CREATED)
    public UserResponse create(@Valid @Body UserRequest request) {
        User user = new User(null, request.email(), request.role(), null);
        return toResponse(userRepository.save(user));
    }

    @Put("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @Body UserRequest request) {
        User user = new User(id, request.email(), request.role(), null);
        return toResponse(userRepository.update(user));
    }

    @Delete("/{id}")
    @Status(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        userRepository.deleteById(id);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.id(), user.email(), user.role(), user.createdAt());
    }
}
