package lol.pbu.kaiju.controller;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.RoleUpdateRequest;
import lol.pbu.kaiju.repository.UserRepository;

import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/admin/users")
@Secured("GLOBAL_ADMIN")
public class AdminUserController {

    private final UserRepository userRepository;

    public AdminUserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Explicit endpoint for role elevation.
     * Prevents Mass Assignment by only accepting a RoleUpdateRequest.
     *
     * @param id      the ID of the user to promote/demote
     * @param request the requested role
     * @return the updated user
     */
    @Put("/{id}/role")
    public User updateUserRole(@PathVariable UUID id, @Valid @Body RoleUpdateRequest request) {
        if (!userRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        userRepository.updateRole(id, request.role());
        return userRepository.findById(id).orElseThrow();
    }
}
