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

import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@MicronautTest(transactional = false)
class DefaultUserServiceSpec extends BaseControllerSpec {

    @Inject
    UserService userService

    def "handles queries, role updates, missing users, and blank email lookups"() {
        given: "a global admin and a standard user"
        def admin = dataBuilder.user().asAdmin().persist()
        def target = dataBuilder.user().asStandardUser().persist()

        expect: "blank and null email lookups return empty and getUsers returns a page"
        verifyAll {
            userService.getUserByEmail(null).isEmpty()
            userService.getUserByEmail("   ").isEmpty()
            userService.getUserByEmail("  ${target.email().toUpperCase(Locale.ROOT)}  ").isPresent()
            !userService.getUsers(CursoredPageable.from(10, Sort.UNSORTED)).content.isEmpty()
        }

        when: "getCurrentUser is called for a missing user"
        userService.getCurrentUser(UUID.randomUUID())

        then:
        HttpStatusException currentEx = thrown()
        currentEx.status == HttpStatus.NOT_FOUND

        when: "updateRole is called for an existing user and then a missing user"
        User promoted = userService.updateRole(target.id(), UserRole.REGION_AGENT, admin.id())

        then:
        promoted.role() == UserRole.REGION_AGENT

        when:
        userService.updateRole(UUID.randomUUID(), UserRole.REGION_AGENT, admin.id())

        then:
        HttpStatusException roleMissingEx = thrown()
        roleMissingEx.status == HttpStatus.NOT_FOUND

        when: "deleteUser is called for a missing user as admin"
        userService.deleteUser(UUID.randomUUID(), admin.id())

        then:
        HttpStatusException deleteMissingEx = thrown()
        deleteMissingEx.status == HttpStatus.NOT_FOUND

        when: "deleteUser is called for an existing user as admin"
        def disposable = dataBuilder.user().asStandardUser().persist()
        userService.deleteUser(disposable.id(), admin.id())

        then:
        userService.getUserById(disposable.id(), admin.id()).isEmpty()
    }

    def "handles null emails on create and update"() {
        given: "a unit-tested DefaultUserService with mocked repository"
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

        when: "createUser and updateUser receive a User record with null email and with mixed-case email"
        User createdNullEmail = unitService.createUser(new User(null, null, null, null), actorId)
        User createdValidEmail = unitService.createUser(new User(null, "  Created@Example.COM ", null, null), actorId)
        User updatedNullEmail = unitService.updateUser(targetId, new User(targetId, null, null, null), actorId)
        User updatedValidEmail = unitService.updateUser(targetId, new User(targetId, "  Updated@Example.COM ", null, null), actorId)

        then: "null emails pass through and non-null emails are canonicalized"
        verifyAll {
            createdNullEmail.email() == null
            createdValidEmail.email() == "created@example.com"
            updatedNullEmail.email() == null
            updatedValidEmail.email() == "updated@example.com"
        }
    }

    def "handles non-unique and missing database errors during user provisioning"() {
        when: "provisionOrGetUser hits a RuntimeException wrapping a non-unique SQLException"
        UserRepository repo1 = Mock(UserRepository)
        DefaultUserService svc1 = new DefaultUserService(
                repo1,
                new AuthorizationService(Mock(PermissionQueryRepository), repo1, Mock(LocationRepository), Mock(BoundaryRepository), new GeometryFactory())
        )
        String failEmail1 = "db-fail-${UUID.randomUUID()}@example.com"
        repo1.findByEmail(failEmail1) >> Optional.empty()
        repo1.save(_ as User) >> {
            throw new IllegalStateException("Connection error", new SQLException("Lost link", "08001"))
        }
        svc1.provisionOrGetUser(failEmail1)

        then: "the original RuntimeException is rethrown"
        IllegalStateException nonUniqueEx = thrown()
        nonUniqueEx.message == "Connection error"

        when: "provisionOrGetUser hits a RuntimeException with no SQLException cause"
        UserRepository repo2 = Mock(UserRepository)
        DefaultUserService svc2 = new DefaultUserService(
                repo2,
                new AuthorizationService(Mock(PermissionQueryRepository), repo2, Mock(LocationRepository), Mock(BoundaryRepository), new GeometryFactory())
        )
        String failEmail2 = "plain-fail-${UUID.randomUUID()}@example.com"
        repo2.findByEmail(failEmail2) >> Optional.empty()
        repo2.save(_ as User) >> {
            throw new IllegalArgumentException("Unexpected failure")
        }
        svc2.provisionOrGetUser(failEmail2)

        then:
        IllegalArgumentException plainEx = thrown()
        plainEx.message == "Unexpected failure"

        when: "provisionOrGetUser hits unique violation 23505 but the concurrent user still cannot be found"
        UserRepository repo3 = Mock(UserRepository)
        DefaultUserService svc3 = new DefaultUserService(
                repo3,
                new AuthorizationService(Mock(PermissionQueryRepository), repo3, Mock(LocationRepository), Mock(BoundaryRepository), new GeometryFactory())
        )
        String ghostEmail = "ghost-${UUID.randomUUID()}@example.com"
        repo3.findByEmail(ghostEmail) >>> [Optional.empty(), Optional.empty()]
        repo3.save(_ as User) >> {
            throw new RuntimeException("Duplicate key", new SQLException("Unique violation", "23505"))
        }
        svc3.provisionOrGetUser(ghostEmail)

        then:
        IllegalStateException ghostEx = thrown()
        ghostEx.message.contains("User record could not be found after unique violation")
    }
}
