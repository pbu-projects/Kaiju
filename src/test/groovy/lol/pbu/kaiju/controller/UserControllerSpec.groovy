package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.dto.CreateUserCommand
import lol.pbu.kaiju.dto.UpdateUserCommand
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository
import net.datafaker.Faker
import spock.lang.Shared
import spock.lang.Unroll

import java.time.OffsetDateTime
import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class UserControllerSpec extends BaseControllerSpec {

    @Inject
    UserRepository userRepository

    @Inject
    UserController userController

    @Shared
    Faker faker = new Faker()

    List<UUID> createdUserIds = []

    def cleanup() {
        createdUserIds.each { id ->
            try {
                executeUpdate("DELETE FROM users WHERE id = ?", id)
            } catch (Exception ignored) {
            }
        }
        createdUserIds.clear()
    }

    private User createDbUser(String email, UserRole role) {
        User user = userRepository.save(new User(null, email, role, OffsetDateTime.now()))
        createdUserIds.add(user.id())
        return user
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid user with default role via HTTP POST"() {
        given: "a create user command without role specified"
        String email = "test-user-${faker.number().digits(6)}@example.com"
        def command = new CreateUserCommand(email, null)

        when: "the user is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/users", command)), User)
        User saved = response.body()
        if (saved?.id() != null) {
            createdUserIds.add(saved.id())
        }

        then: "the user is persisted with 200 OK and default STANDARD_USER role"
        response.status == HttpStatus.OK
        saved.id() != null
        saved.email() == email
        saved.role() == UserRole.STANDARD_USER

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM users WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.email() == email
            saved.role().name() == role
        }
    }

    @Unroll
    def "CREATE | should fail to save user with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add a user with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/users", payload)), User)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase                | payload
        "Null Email"            | [email: null]
        "Blank Email"           | [email: "   "]
        "Invalid Email Format"  | [email: "invalid-email"]
        "Email Too Long"        | [email: "A" * 256 + "@example.com"]
    }

    def "CREATE | should reject unauthenticated POST /users with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to create a user"
        client.exchange(HttpRequest.POST("/users", new CreateUserCommand("unauth@example.com", null)), User)

        then: "a 401 UNAUTHORIZED status is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** AUTHORIZATION / ROLE ESCALATION Tests **********/

    def "AUTHORIZATION | should throw 403 when non-admin attempts to create a user with elevated role"() {
        given: "a standard user caller"
        def caller = createDbUser("std-caller-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)
        def command = new CreateUserCommand("rogue-admin-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN)

        when: "the standard user attempts to create a global admin"
        client.exchange(authenticated(HttpRequest.POST("/users", command), caller.id().toString(), ["STANDARD_USER"]), User)

        then: "a 403 Forbidden is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "AUTHORIZATION | should allow admin to create a user with elevated role"() {
        given: "an admin caller"
        def adminCaller = createDbUser("admin-caller-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN)
        def command = new CreateUserCommand("elevated-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN)

        when: "the admin creates an elevated user"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/users", command), adminCaller.id().toString()), User)
        User saved = response.body()
        if (saved?.id() != null) {
            createdUserIds.add(saved.id())
        }

        then: "the user is saved with the elevated role"
        response.status == HttpStatus.OK
        saved.role() == UserRole.GLOBAL_ADMIN
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing user by ID when requested by self"() {
        given: "an existing user"
        def user = createDbUser("read-self-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)

        when: "the user is requested by its ID via HTTP GET"
        def response = client.exchange(authenticated(HttpRequest.GET("/users/${user.id()}"), user.id().toString(), ["STANDARD_USER"]), User)

        then: "200 OK is returned with the correct user"
        response.status == HttpStatus.OK
        response.body().id() == user.id()
        response.body().email() == user.email()
    }

    def "READ | should allow admin to view any user profile"() {
        given: "a standard user and an admin"
        def user = createDbUser("view-target-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)
        def admin = createDbUser("view-admin-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN)

        when: "the admin views the user profile via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/users/${user.id()}"), admin.id().toString()), User)

        then: "200 OK is returned with the target user"
        response.status == HttpStatus.OK
        response.body().id() == user.id()
    }

    def "READ | IDOR | should throw 403 when user attempts to view another user's profile"() {
        given: "two standard users"
        def userA = createDbUser("user-a-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)
        def userB = createDbUser("user-b-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)

        when: "user A attempts to view user B's profile via HTTP GET"
        client.exchange(authenticated(HttpRequest.GET("/users/${userB.id()}"), userA.id().toString(), ["STANDARD_USER"]), User)

        then: "a 403 Forbidden is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "READ | should return 404 for a non-existent user ID"() {
        given: "an admin caller and a non-existent UUID"
        def admin = createDbUser("admin-404-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN)
        def nonExistentId = UUID.randomUUID()

        when: "a non-existent user is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/users/${nonExistentId}"), admin.id().toString()), User)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing user profile while retaining role"() {
        given: "an existing user updating their own profile"
        def user = createDbUser("update-self-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)
        String newEmail = "updated-${faker.number().digits(5)}@example.com"
        def command = new UpdateUserCommand(newEmail)

        when: "the user updates themselves via HTTP PUT"
        def response = client.exchange(authenticated(HttpRequest.PUT("/users/${user.id()}", command), user.id().toString(), ["STANDARD_USER"]), User)
        User updated = response.body()

        then: "the returned user contains updated email and retains original role"
        response.status == HttpStatus.OK
        updated.id() == user.id()
        updated.email() == newEmail
        updated.role() == UserRole.STANDARD_USER

        and: "the database reflects the update"
        def result = sql.firstRow("SELECT email, role FROM users WHERE id = ?", [user.id()])
        verifyAll(result) {
            email == newEmail
            role == 'STANDARD_USER'
        }
    }

    def "UPDATE | IDOR | should throw 403 Forbidden when standard user attempts to update another user"() {
        given: "a victim and an attacker"
        def victim = createDbUser("victim-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)
        def attacker = createDbUser("attacker-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)
        def command = new UpdateUserCommand("hacked-${faker.number().digits(5)}@example.com")

        when: "attacker attempts to update victim via HTTP PUT"
        client.exchange(authenticated(HttpRequest.PUT("/users/${victim.id()}", command), attacker.id().toString(), ["STANDARD_USER"]), User)

        then: "a 403 Forbidden is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should fail to update a non-existent user when called by admin"() {
        given: "an admin and a random non-existent ID"
        def admin = createDbUser("admin-upd-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN)
        def nonExistentId = UUID.randomUUID()
        def command = new UpdateUserCommand("notfound-${faker.number().digits(5)}@example.com")

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/users/${nonExistentId}", command), admin.id().toString()), User)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing user when requested by self"() {
        given: "a user deleting their own account"
        def user = createDbUser("del-self-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)

        when: "the user deletes themselves via HTTP DELETE"
        def response = client.exchange(authenticated(HttpRequest.DELETE("/users/${user.id()}"), user.id().toString(), ["STANDARD_USER"]))

        then: "the response is 200 OK"
        response.status == HttpStatus.OK

        and: "the user is removed from the database"
        sql.firstRow("SELECT count(*) as count FROM users WHERE id = ?", [user.id()]).count == 0
    }

    def "DELETE | IDOR | should throw 403 Forbidden when standard user attempts to delete another user"() {
        given: "a victim and an attacker"
        def victim = createDbUser("victim-del-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)
        def attacker = createDbUser("attacker-del-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)

        when: "the attacker attempts to delete the victim via HTTP DELETE"
        client.exchange(authenticated(HttpRequest.DELETE("/users/${victim.id()}"), attacker.id().toString(), ["STANDARD_USER"]))

        then: "a 403 Forbidden is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should fail to delete a non-existent user when called by admin"() {
        given: "an admin and a random non-existent ID"
        def admin = createDbUser("admin-del-ne-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN)
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/users/${nonExistentId}"), admin.id().toString()))

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve users with pagination when called by admin"() {
        given: "an admin user in the system"
        def admin = createDbUser("admin-list-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN)

        when: "requesting users via HTTP GET with admin credentials"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/users?size=5"), admin.id().toString()), Map)

        then: "the response is 200 OK with paginated content"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
        body.content.size() >= 1
    }

    def "LIST | should reject unauthenticated GET /users with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to list users"
        client.exchange(HttpRequest.GET("/users"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "LIST | should fully drain all users sequentially using cursors"() {
        setup:
        (1..2).each {
            createDbUser("drain-${it}-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)
        }
        Set<User> allUsers = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("email")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<User> page = userController.getUsers(pageable)
            allUsers.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all users from the database"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM users").count
        verifyAll {
            allUsers.size() == totalCount
            allUsers.size() >= 2
        }
    }

    def "LIST | should reject standard user GET /users with 403 FORBIDDEN"() {
        given: "a standard authenticated user"
        def user = createDbUser("std-list-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER)

        when: "attempting to list users with standard role"
        client.exchange(authenticated(HttpRequest.GET("/users"), user.id().toString(), ["STANDARD_USER"]))

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }
}
