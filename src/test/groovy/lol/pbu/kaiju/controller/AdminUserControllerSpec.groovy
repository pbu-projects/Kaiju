package lol.pbu.kaiju.controller

import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.RoleUpdateRequest
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository

import java.time.OffsetDateTime

class AdminUserControllerSpec extends BaseControllerSpec {

    @Inject
    UserRepository userRepository

    @Inject
    AdminUserController adminUserController

    def "updateUserRole updates the role of an existing user"() {
        given: "a user saved with STANDARD_USER role"
        User user = userRepository.save(new User(null, "admin-test-${UUID.randomUUID()}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))

        when: "an admin promotes them to REGION_AGENT"
        User result = adminUserController.updateUserRole(user.id(), new RoleUpdateRequest(UserRole.REGION_AGENT))

        then: "the returned user has the updated role"
        result.role() == UserRole.REGION_AGENT

        and: "the database reflects the change"
        userRepository.findById(user.id()).get().role() == UserRole.REGION_AGENT
    }

    def "updateUserRole throws 404 when user does not exist"() {
        given: "a UUID that does not correspond to any user"
        UUID nonExistentId = UUID.randomUUID()

        when: "an admin attempts to update a role for an unknown user"
        adminUserController.updateUserRole(nonExistentId, new RoleUpdateRequest(UserRole.GLOBAL_ADMIN))

        then: "a 404 Not Found is thrown"
        HttpStatusException e = thrown()
        e.status == HttpStatus.NOT_FOUND
    }
}
