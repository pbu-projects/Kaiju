package lol.pbu.kaiju.controller;

import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.RoleUpdateRequest;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.util.ControllerUtils;

import java.util.UUID;

import static io.micronaut.scheduling.TaskExecutors.BLOCKING;
import static lol.pbu.kaiju.model.UserRole.GLOBAL_ADMIN;

@ExecuteOn(BLOCKING)
@Controller("/admin/users")
@Secured(GLOBAL_ADMIN)
public class AdminUserController implements ControllerUtils {

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
        checkExists(userRepository, id);
        userRepository.updateRole(id, request.role());
        return userRepository.findById(id).orElseThrow();
    }
}
