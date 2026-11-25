package lol.pbu.kaiju.controller;

import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.RoleUpdateRequest;
import lol.pbu.kaiju.service.UserService;

import java.security.Principal;
import java.util.UUID;

import static lol.pbu.kaiju.security.Permission.SYSTEM_ADMIN_CLAIM;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Controller("/admin/users")
@Secured(SYSTEM_ADMIN_CLAIM)
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Explicit endpoint for role elevation.
     * Prevents Mass Assignment by only accepting a RoleUpdateRequest.
     *
     * @param id        the ID of the user to promote/demote
     * @param request   the requested role
     * @param principal the authenticated caller
     * @return the updated user
     */
    @Put("/{id}/role")
    public User updateUserRole(@PathVariable UUID id, @Valid @Body RoleUpdateRequest request, Principal principal) {
        UUID actorUserId = UUID.fromString(principal.getName());
        return userService.updateRole(id, request.role(), actorUserId);
    }
}
