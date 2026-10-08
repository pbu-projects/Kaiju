package lol.pbu.kaiju.controller


import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository
import net.datafaker.Faker
import spock.lang.Shared
import spock.lang.Unroll

import java.security.Principal
import java.time.OffsetDateTime

class UserControllerSpec extends BaseControllerSpec {

    @Inject
    UserRepository userRepository

    @Inject
    UserController userController

    @Shared
    Faker faker = new Faker()

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid user"() {
        given: "a new valid user"
        def newUser = new User(
                null,
                faker.internet().emailAddress(),
                UserRole.STANDARD_USER,
                OffsetDateTime.now()
        )

        when: "the user is added"
        User saved = userController.addUser(newUser)

        then: "the user is persisted with a generated ID"
        verifyAll {
            saved.id() != null
            saved.email() == newUser.email()
            saved.role() == newUser.role()
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM users WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.email() == email
            saved.role().name() == role
        }
    }

    @Unroll
    def "CREATE | should fail to save user with invalid data: #testCase"(String testCase, User user) {
        when: "an attempt is made to add a user with invalid data"
        userController.addUser(user)

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, user] << {
            def validData = [
                    email: "test@example.com",
                    role : UserRole.STANDARD_USER
            ]

            def invalidCases = [
                    [field: 'email', value: null, caseName: "Null Email"],
                    [field: 'email', value: ' ', caseName: "Blank Email"],
                    [field: 'email', value: "invalid-email", caseName: "Invalid Email Format"],
                    [field: 'email', value: 'A' * 256 + "@example.com", caseName: "Email Too Long"],
                    [field: 'role', value: null, caseName: "Null Role"]
            ]

            return invalidCases.collect { invalidCase ->
                def props = new HashMap(validData)
                props[invalidCase.field] = invalidCase.value
                def u = new User(
                        null,
                        props.email as String,
                        props.role as UserRole,
                        OffsetDateTime.now()
                )
                [invalidCase.caseName, u]
            }
        }()
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing user by ID"() {
        given: "an existing user"
        def user = userRepository.save(new User(null, "test-user-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))
        UUID id = user.id()

        when: "the user is requested by its ID"
        def result = userController.getUser(id)

        then: "the correct user is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
            result.get().email() == user.email()
        }
    }

    def "READ | should return empty for a non-existent user ID"() {
        when: "a non-existent user is requested"
        def result = userController.getUser(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    Principal createPrincipal(UUID userId) {
        new Principal() {
            @Override
            String getName() {
                return userId.toString()
            }
        }
    }

    /********** UPDATE Tests **********/

    // SECURITY REGRESSION TEST: Verifies that Mass Assignment vulnerabilities are blocked.
    // Ensure that users can update their allowed profile fields (like email) but cannot
    // elevate their own privileges by sneaking a 'role' field into the payload.
    def "UPDATE | should successfully update an existing user while ignoring role changes"() {
        given: "an existing user updating their own profile"
        def user = userRepository.save(new User(null, "orig-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))
        UUID id = user.id()
        def newEmail = faker.internet().emailAddress()
        def updateRequest = new User(null, newEmail, UserRole.REGION_DIRECTOR, OffsetDateTime.now())

        when: "the user updates themselves"
        User updated = userController.updateUser(id, updateRequest, createPrincipal(id))

        then: "the returned user contains the updated email but retains the original role"
        verifyAll {
            updated.id() == id
            updated.email() == newEmail
            updated.role() == UserRole.STANDARD_USER
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT email, role FROM users WHERE id = ?", [id])
        verifyAll(dbResult) {
            email == newEmail
            role == 'STANDARD_USER'
        }
    }

    def "UPDATE | should throw 403 Forbidden when standard user attempts to update another user"() {
        given: "two users"
        def victim = userRepository.save(new User(null, "victim-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))
        def attacker = userRepository.save(new User(null, "attacker-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))
        def updateRequest = new User(null, "hacked@example.com", UserRole.STANDARD_USER, OffsetDateTime.now())

        when: "attacker attempts to update victim"
        userController.updateUser(victim.id(), updateRequest, createPrincipal(attacker.id()))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status.code == 403
    }

    def "UPDATE | should fail to update a non-existent user when called by admin"() {
        given: "a global admin and a random non-existent ID"
        def admin = userRepository.save(new User(null, "admin-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN, OffsetDateTime.now()))
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new User(null, "test@example.com", UserRole.STANDARD_USER, OffsetDateTime.now())

        when: "an update is attempted by admin"
        userController.updateUser(nonExistentId, updateRequest, createPrincipal(admin.id()))

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }


    /********** DELETE Tests **********/

    def "DELETE | should remove an existing user when requested by self"() {
        given: "a new user to be deleted"
        def tempUser = new User(
                null,
                "delete-me@example.org",
                UserRole.STANDARD_USER,
                OffsetDateTime.now()
        )
        def saved = userController.addUser(tempUser)
        UUID id = saved.id()
        assert userRepository.existsById(id)

        when: "the user deletes themselves"
        userController.deleteUser(id, createPrincipal(id))

        then: "the user no longer exists in the repository or database"
        verifyAll {
            !userRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM users WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should throw 403 Forbidden when standard user attempts to delete another user"() {
        given: "two users"
        def victim = userRepository.save(new User(null, "victim-del-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))
        def attacker = userRepository.save(new User(null, "attacker-del-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))

        when: "attacker attempts to delete victim"
        userController.deleteUser(victim.id(), createPrincipal(attacker.id()))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status.code == 403
    }

    def "DELETE | should fail to delete a non-existent user when called by admin"() {
        given: "a global admin and a random non-existent ID"
        def admin = userRepository.save(new User(null, "admin-del-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN, OffsetDateTime.now()))
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted by admin"
        userController.deleteUser(nonExistentId, createPrincipal(admin.id()))

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }


    /********** LIST Tests **********/

    def "LIST | should fully drain all users sequentially using cursors"() {
        setup:
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

    /********** AUTHORIZATION Tests **********/

    def "AUTHORIZATION | should throw 403 when non-admin attempts to create a user with elevated role"() {
        given: "a standard user and an attempt to create a GLOBAL_ADMIN user"
        def standardUser = userRepository.save(new User(null, "std-create-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))
        def adminTarget = new User(null, "rogue-admin-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN, OffsetDateTime.now())

        when: "the standard user attempts to create the global admin"
        userController.addUser(adminTarget, createPrincipal(standardUser.id()))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status.code == 403
    }

    def "AUTHORIZATION | should throw 403 when user attempts to view another user's profile"() {
        given: "two separate standard users"
        def userA = userRepository.save(new User(null, "usera-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))
        def userB = userRepository.save(new User(null, "userb-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))

        when: "user A attempts to view user B's profile"
        userController.getUser(userB.id(), createPrincipal(userA.id()))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status.code == 403
    }

    def "AUTHORIZATION | should allow user to view their own profile and admin to view any profile"() {
        given: "a standard user and an admin"
        def userA = userRepository.save(new User(null, "user-self-${faker.number().digits(5)}@example.com", UserRole.STANDARD_USER, OffsetDateTime.now()))
        def admin = userRepository.save(new User(null, "admin-view-${faker.number().digits(5)}@example.com", UserRole.GLOBAL_ADMIN, OffsetDateTime.now()))

        when: "user views their own profile"
        def selfView = userController.getUser(userA.id(), createPrincipal(userA.id()))

        then: "it succeeds"
        selfView.isPresent()
        selfView.get().id() == userA.id()

        when: "admin views user profile"
        def adminView = userController.getUser(userA.id(), createPrincipal(admin.id()))

        then: "it succeeds"
        adminView.isPresent()
        adminView.get().id() == userA.id()
    }
}
