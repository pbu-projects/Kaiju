package lol.pbu.kaiju.service

import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.BoundaryRepository
import lol.pbu.kaiju.repository.LocationRepository
import lol.pbu.kaiju.repository.PermissionQueryRepository
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.security.AuthorizationService
import org.locationtech.jts.geom.GeometryFactory
import spock.lang.Unroll

import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@MicronautTest(transactional = false)
class DefaultUserServiceSpec extends BaseControllerSpec {

    @Inject
    UserService userService

    private Map createMockUserHarness() {
        UserRepository mockUserRepo = Mock(UserRepository)
        AuthorizationService unitAuth = new AuthorizationService(
                Mock(PermissionQueryRepository),
                mockUserRepo,
                Mock(LocationRepository),
                Mock(BoundaryRepository),
                new GeometryFactory()
        )
        DefaultUserService unitService = new DefaultUserService(mockUserRepo, unitAuth)

        UUID actorId = UUID.randomUUID()
        UUID targetId = UUID.randomUUID()
        User admin = new User(actorId, "admin@example.com", UserRole.GLOBAL_ADMIN, OffsetDateTime.now(ZoneOffset.UTC))
        User existing = new User(targetId, "old@example.com", UserRole.STANDARD_USER, OffsetDateTime.now(ZoneOffset.UTC))

        mockUserRepo.findById(actorId) >> Optional.of(admin)
        mockUserRepo.save(_ as User) >> { User u -> u.withId(targetId) }
        mockUserRepo.findById(targetId) >> Optional.of(existing)
        mockUserRepo.update(_ as User) >> { User u -> u }

        [
                unitService : unitService,
                mockUserRepo: mockUserRepo,
                actorId     : actorId,
                targetId    : targetId
        ]
    }

    @Unroll
    def "getUserByEmail returns #expectedPresent when looking up #scenario"() {
        given: "a persisted standard user"
        def target = dataBuilder.user().asStandardUser().persist()
        String query = useTargetUpperPadded ? "  ${target.email().toUpperCase(Locale.ROOT)}  " : rawInput

        when: "looking up the user by email"
        Optional<User> result = userService.getUserByEmail(query)

        then: "presence matches expectation"
        result.isPresent() == expectedPresent

        where:
        scenario                   | rawInput | useTargetUpperPadded || expectedPresent
        "null email"               | null     | false                || false
        "blank email"              | "   "    | false                || false
        "padded uppercase email"   | null     | true                 || true
    }

    def "getUsers returns a non-empty page of users"() {
        given: "a persisted user"
        dataBuilder.user().asStandardUser().persist()

        when: "listing users"
        def page = userService.getUsers(CursoredPageable.from(10, Sort.UNSORTED))

        then: "the page contains users"
        !page.content.isEmpty()
    }

    def "getCurrentUser throws NOT_FOUND when the user does not exist"() {
        when: "fetching the current user for an unknown ID"
        userService.getCurrentUser(UUID.randomUUID())

        then: "NOT_FOUND is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.NOT_FOUND
    }

    def "updateRole updates the role of an existing user"() {
        given: "a global admin and a standard user"
        def admin = dataBuilder.user().asAdmin().persist()
        def target = dataBuilder.user().asStandardUser().persist()

        when: "promoting the user to REGION_AGENT"
        User promoted = userService.updateRole(target.id(), UserRole.REGION_AGENT, admin.id())

        then: "the returned user has the updated role"
        promoted.role() == UserRole.REGION_AGENT
    }

    def "updateRole throws NOT_FOUND when the target user is missing"() {
        given: "a global admin"
        def admin = dataBuilder.user().asAdmin().persist()

        when: "updating the role of a missing user"
        userService.updateRole(UUID.randomUUID(), UserRole.REGION_AGENT, admin.id())

        then: "NOT_FOUND is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.NOT_FOUND
    }

    def "deleteUser throws NOT_FOUND when the target user is missing"() {
        given: "a global admin"
        def admin = dataBuilder.user().asAdmin().persist()

        when: "deleting a missing user"
        userService.deleteUser(UUID.randomUUID(), admin.id())

        then: "NOT_FOUND is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.NOT_FOUND
    }

    def "deleteUser removes an existing user"() {
        given: "a global admin and a disposable user"
        def admin = dataBuilder.user().asAdmin().persist()
        def disposable = dataBuilder.user().asStandardUser().persist()

        when: "deleting the user"
        userService.deleteUser(disposable.id(), admin.id())

        then: "the user can no longer be found"
        userService.getUserById(disposable.id(), admin.id()).isEmpty()
    }

    @Unroll
    def "createUser handles #scenario email"() {
        given: "a mocked DefaultUserService"
        def h = createMockUserHarness()

        when: "creating a user"
        User created = h.unitService.createUser(new User(null, inputEmail, null, null), h.actorId)

        then: "the email matches the expected value"
        created.email() == expectedEmail

        where:
        scenario                   | inputEmail                 || expectedEmail
        "null"                     | null                       || null
        "padded mixed-case"        | "  Created@Example.COM "   || "created@example.com"
    }

    @Unroll
    def "updateUser handles #scenario email"() {
        given: "a mocked DefaultUserService"
        def h = createMockUserHarness()

        when: "updating a user"
        User updated = h.unitService.updateUser(h.targetId, new User(h.targetId, inputEmail, null, null), h.actorId)

        then: "the email matches the expected value"
        updated.email() == expectedEmail

        where:
        scenario                   | inputEmail                 || expectedEmail
        "null"                     | null                       || null
        "padded mixed-case"        | "  Updated@Example.COM "   || "updated@example.com"
    }

    @Unroll
    def "provisionOrGetUser rethrows #expectedType.simpleName when #scenario"() {
        given: "a mocked UserRepository that fails on save"
        UserRepository repo = Mock(UserRepository)
        DefaultUserService svc = new DefaultUserService(
                repo,
                new AuthorizationService(Mock(PermissionQueryRepository), repo, Mock(LocationRepository), Mock(BoundaryRepository), new GeometryFactory())
        )
        String email = "prov-${UUID.randomUUID()}@example.com"
        repo.findByEmail(email) >>> lookupResponses
        repo.save(_ as User) >> { throw thrownError }

        when: "provisioning the user"
        svc.provisionOrGetUser(email)

        then: "the expected exception and message are thrown"
        Throwable ex = thrown(Throwable)
        verifyAll {
            expectedType.isInstance(ex)
            exactMessage ? ex.message == expectedMessage : ex.message.contains(expectedMessage)
        }

        where:
        scenario                                                | thrownError                                                                               | lookupResponses                       || expectedType             | expectedMessage                                         | exactMessage
        "save hits a non-unique SQLException"                   | new IllegalStateException("Connection error", new SQLException("Lost link", "08001"))     | [Optional.empty()]                    || IllegalStateException    | "Connection error"                                      | true
        "save hits a RuntimeException without SQLException"     | new IllegalArgumentException("Unexpected failure")                                        | [Optional.empty()]                    || IllegalArgumentException | "Unexpected failure"                                    | true
        "unique violation occurs and user still cannot be found"| new RuntimeException("Duplicate key", new SQLException("Unique violation", "23505"))      | [Optional.empty(), Optional.empty()]  || IllegalStateException    | "User record could not be found after unique violation" | false
    }
}
