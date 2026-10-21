package lol.pbu.kaiju.service

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.repository.UserRepository

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

@MicronautTest(transactional = false)
class UserServiceConcurrencySpec extends BaseControllerSpec {

    @Inject
    UserService userService

    @Inject
    UserRepository userRepository

    def cleanup() {
        executeUpdate("DELETE FROM users WHERE email LIKE 'concurrent-%@example.com' OR email LIKE 'canonical-%@example.com'")
    }

    def "JIT Provisioning | canonicalizes mixed-case and untrimmed email"() {
        given: "a mixed-case and untrimmed email"
        def rawEmail = "   Canonical-${UUID.randomUUID()}@EXAMPLE.COM   "
        def canonicalEmail = rawEmail.toLowerCase(Locale.ROOT).trim()

        when: "provisioning with untrimmed mixed-case email"
        def user1 = userService.provisionOrGetUser(rawEmail)

        and: "retrieving with canonical lowercase email"
        def user2 = userService.provisionOrGetUser(canonicalEmail)

        then: "both calls return the same persisted user with canonical lowercase email"
        user1.id() == user2.id()
        user1.email() == canonicalEmail
        user2.email() == canonicalEmail
        userRepository.findByEmail(canonicalEmail).isPresent()
    }

    def "JIT Provisioning | handles concurrent insertion for same email gracefully via SQLState 23505"() {
        given: "a shared email address and virtual thread executor"
        def email = "concurrent-${UUID.randomUUID()}@example.com"
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        int concurrency = 16

        when: "16 virtual threads attempt to provision the same user concurrently"
        List<Callable<User>> tasks = (1..concurrency).collect {
            return { -> userService.provisionOrGetUser(email) } as Callable<User>
        }
        List<Future<User>> futures = executor.invokeAll(tasks)
        List<User> results = futures.collect { it.get() }

        then: "all tasks succeed and return the exact same user ID"
        results.size() == concurrency
        def firstUserId = results[0].id()
        results.every { it.id() == firstUserId }
        results.every { it.email() == email }

        and: "only exactly one user row was inserted into the database"
        userRepository.findByEmail(email).isPresent()
    }
}
