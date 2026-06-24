package lol.pbu.kaiju.core.controller


import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.core.domain.User
import lol.pbu.kaiju.core.model.UserRole
import lol.pbu.kaiju.core.repository.UserRepository
import net.datafaker.Faker
import spock.lang.Shared
import spock.lang.Unroll

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
                UserRole.VOLUNTEER,
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
                    role : UserRole.VOLUNTEER
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

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "READ | should retrieve an existing user by ID: #email"(UUID id, String email) {
        when: "the user is requested by its ID"
        def result = userController.getUser(id)

        then: "the correct user is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
            result.get().email() == email
        }

        where:
        [id, email] << sql.rows("SELECT id, email FROM users LIMIT 3").collect { [it.id, it.email] }
    }

    def "READ | should return empty for a non-existent user ID"() {
        when: "a non-existent user is requested"
        def result = userController.getUser(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "UPDATE | should successfully update an existing user: #originalEmail"(UUID id, String originalEmail) {
        given: "an existing user's details"
        def newEmail = faker.internet().emailAddress()
        def updateRequest = new User(null, newEmail, UserRole.MODERATOR, OffsetDateTime.now())

        when: "the user is updated"
        User updated = userController.updateUser(id, updateRequest)

        then: "the returned user contains the updated data"
        verifyAll {
            updated.id() == id
            updated.email() == newEmail
            updated.role() == UserRole.MODERATOR
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT email, role FROM users WHERE id = ?", [id])
        verifyAll(dbResult) {
            email == newEmail
            role == 'MODERATOR'
        }

        where:
        [id, originalEmail] << sql.rows("SELECT id, email FROM users LIMIT 2").collect { [it.id, it.email] }
    }

    def "UPDATE | should fail to update a non-existent user"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new User(null, "test@example.com", UserRole.VOLUNTEER, OffsetDateTime.now())

        when: "an update is attempted"
        userController.updateUser(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should handle update of non-existent user gracefully when using no-look"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new User(null, "test@example.com", UserRole.VOLUNTEER, OffsetDateTime.now())

        when: "a no-look update is attempted"
        userController.updateUserNoLook(nonExistentId, updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing user"() {
        given: "a new user to be deleted"
        def tempUser = new User(
                null,
                "delete-me@example.org",
                UserRole.VOLUNTEER,
                OffsetDateTime.now()
        )
        def saved = userController.addUser(tempUser)
        UUID id = saved.id()
        assert userRepository.existsById(id)

        when: "the user is deleted"
        userController.deleteUser(id)

        then: "the user no longer exists in the repository or database"
        verifyAll {
            !userRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM users WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent user"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted"
        userController.deleteUser(nonExistentId)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "DELETE | should handle deletion of non-existent user gracefully when using no-look"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a no-look delete is attempted"
        userController.deleteUserNoLook(nonExistentId)

        then: "no exception is thrown"
        noExceptionThrown()
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
}
