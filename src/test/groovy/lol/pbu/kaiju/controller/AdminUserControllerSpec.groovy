package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.RoleUpdateRequest
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository

import java.time.OffsetDateTime
import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class AdminUserControllerSpec extends BaseControllerSpec {

    @Inject
    UserRepository userRepository

    def cleanup() {
        executeUpdate("DELETE FROM users WHERE email LIKE 'admin-test-%'")
    }

    def "updateUserRole updates the role of an existing user"() {
        given: "a user saved with STANDARD_USER role"
        User user = userRepository.save(new User(null, "admin-test-${UUID.randomUUID()}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))

        when: "an admin promotes them to REGION_AGENT via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/admin/users/${user.id()}/role", new RoleUpdateRequest(UserRole.REGION_AGENT))), User)
        User result = response.body()

        then: "the returned user has the updated role"
        response.status == HttpStatus.OK
        result.role() == UserRole.REGION_AGENT

        and: "the database reflects the change"
        userRepository.findById(user.id()).get().role() == UserRole.REGION_AGENT
    }

    def "updateUserRole throws 404 when user does not exist"() {
        given: "a UUID that does not correspond to any user"
        UUID nonExistentId = UUID.randomUUID()

        when: "an admin attempts to update a role for an unknown user via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/admin/users/${nonExistentId}/role", new RoleUpdateRequest(UserRole.GLOBAL_ADMIN))), User)

        then: "a 404 Not Found is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "updateUserRole throws 403 FORBIDDEN for standard user without SYSTEM_ADMIN_CLAIM"() {
        given: "a user saved with STANDARD_USER role"
        User user = userRepository.save(new User(null, "admin-test-${UUID.randomUUID()}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))

        when: "a standard user attempts to update role"
        client.exchange(authenticated(HttpRequest.PUT("/admin/users/${user.id()}/role", new RoleUpdateRequest(UserRole.REGION_AGENT)), UUID.randomUUID().toString(), ["STANDARD_USER"]), User)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "updateUserRole throws 401 UNAUTHORIZED for unauthenticated caller"() {
        when: "an unauthenticated caller attempts to update a role"
        client.exchange(HttpRequest.PUT("/admin/users/${UUID.randomUUID()}/role", new RoleUpdateRequest(UserRole.REGION_AGENT)), User)

        then: "a 401 UNAUTHORIZED is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }
}
