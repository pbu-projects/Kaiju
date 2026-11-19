package lol.pbu.kaiju.security

import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.service.UserServiceImpl
import spock.lang.Specification
import spock.lang.Unroll

@MicronautTest(transactional = true)
class AuthAndAccountEdgeCasesSpec extends Specification {

    @Inject
    AuthentikAuthenticationMapper mapper

    @Inject
    UserServiceImpl userService

    @Inject
    UserRepository userRepository

    private static OpenIdClaims claimsFor(String email, Object emailVerified, Object groups = null) {
        return [
                getEmail: { -> email },
                get: { String key ->
                    if (key == "email_verified") {
                        return emailVerified
                    }
                    if (key == IdpRoleMapper.CLAIMS_GROUPS_KEY) {
                        return groups
                    }
                    return null
                }
        ] as OpenIdClaims
    }

    @Unroll
    def "login is blocked when email_verified is '#verifiedValue'"() {
        given: "an account login attempt with an unverified or missing email flag"
        String email = "blocked-${UUID.randomUUID()}@kaiju.local"
        OpenIdClaims claims = claimsFor(email, verifiedValue)

        when: "the login mapper checks the token"
        AuthenticationResponse response = AuthentikAuthenticationMapperSpec.await(
                mapper.createAuthenticationResponse("authentik", new OpenIdTokenResponse(), claims, null)
        )

        then: "login fails and no new account is created"
        verifyAll {
            !response.isAuthenticated()
            response.getMessage().isPresent()
            response.getMessage().get() == "Email is not verified in OpenID claims"
            userRepository.findByEmail(email).isEmpty()
        }

        where:
        verifiedValue << [false, "false", null]
    }

    @Unroll
    def "login is blocked when email is missing or blank (#emailValue)"() {
        given: "a login attempt with no usable email address"
        OpenIdClaims claims = claimsFor(emailValue, true)

        when: "the login mapper checks the token"
        AuthenticationResponse response = AuthentikAuthenticationMapperSpec.await(
                mapper.createAuthenticationResponse("authentik", new OpenIdTokenResponse(), claims, null)
        )

        then: "login fails with a missing email error"
        verifyAll(response) {
            !isAuthenticated()
            getMessage().isPresent()
            getMessage().get() == "No email present in OpenID claims"
        }

        where:
        emailValue << [null, "", "   "]
    }

    @Unroll
    def "unknown or empty login groups '#groupClaim' always start as a standard user"() {
        given: "a new user logging in with unknown or empty groups"
        String email = "safe-default-${UUID.randomUUID()}@kaiju.local"
        OpenIdClaims claims = claimsFor(email, true, groupClaim)

        when: "the login mapper creates their session and account"
        AuthenticationResponse response = AuthentikAuthenticationMapperSpec.await(
                mapper.createAuthenticationResponse("authentik", new OpenIdTokenResponse(), claims, null)
        )

        then: "they get standard user access and are never raised to admin or staff roles"
        Optional<User> savedUser = userRepository.findByEmail(email)
        verifyAll {
            response.isAuthenticated()
            response.getAuthentication().isPresent()
            response.getAuthentication().get().getRoles().contains(UserRole.STANDARD_USER.name())
            !response.getAuthentication().get().getRoles().contains(UserRole.GLOBAL_ADMIN.name())
            !response.getAuthentication().get().getRoles().contains(UserRole.REGION_DIRECTOR.name())
            !response.getAuthentication().get().getRoles().contains(UserRole.REGION_AGENT.name())
            !response.getAuthentication().get().getRoles().contains(Permission.SYSTEM_ADMIN.getClaim())
            savedUser.isPresent()
            savedUser.get().role() == UserRole.STANDARD_USER
        }

        where:
        groupClaim << [null, [], ["Unknown Group"], ["Super Admins"], ["Kaiju Org Admins"]]
    }

    def "getUserByEmail finds an account even with uppercase letters and extra spaces"() {
        when: "looking up Victor with uppercase letters and leading and trailing spaces"
        Optional<User> found = userService.getUserByEmail("  Victor.Volunteer1@KAIJU.LOCAL  ")

        then: "his account is returned with its saved lowercase email"
        verifyAll {
            found.isPresent()
            found.get().id() == TestPersonas.VICTOR_VOLUNTEER.id
            found.get().email() == "victor.volunteer1@kaiju.local"
            found.get().role() == TestPersonas.VICTOR_VOLUNTEER.systemRole
        }
    }

    @Unroll
    def "getUserByEmail returns empty without crashing for '#queryEmail'"() {
        when: "looking up a null, blank, or unknown email"
        Optional<User> found = userService.getUserByEmail(queryEmail)

        then: "no account is found and no error is thrown"
        found.isEmpty()

        where:
        queryEmail << [null, "", "   ", "nobody-${UUID.randomUUID()}@kaiju.local"]
    }

    def "provisionOrGetUser cleans email text and keeps an existing user role unchanged"() {
        given: "a brand new user provisioned with mixed-case email and region director role"
        String rawEmail = "  Mixed.Case-${UUID.randomUUID()}@KAIJU.LOCAL  "
        String cleanEmail = rawEmail.toLowerCase(Locale.ROOT).trim()
        User created = userService.provisionOrGetUser(rawEmail, UserRole.REGION_DIRECTOR)

        when: "the same user is looked up again with different role hints"
        User afterGlobalAdminHint = userService.provisionOrGetUser("  ${cleanEmail.toUpperCase(Locale.ROOT)} ", UserRole.GLOBAL_ADMIN)
        User afterStandardHint = userService.provisionOrGetUser(cleanEmail, UserRole.STANDARD_USER)

        then: "the saved email stays clean and the original database role is not overwritten"
        Optional<User> dbUser = userService.getUserByEmail(cleanEmail)
        verifyAll {
            created.email() == cleanEmail
            created.role() == UserRole.REGION_DIRECTOR
            afterGlobalAdminHint.id() == created.id()
            afterGlobalAdminHint.email() == cleanEmail
            afterGlobalAdminHint.role() == UserRole.REGION_DIRECTOR
            afterStandardHint.id() == created.id()
            afterStandardHint.role() == UserRole.REGION_DIRECTOR
            dbUser.isPresent()
            dbUser.get().id() == created.id()
            dbUser.get().role() == UserRole.REGION_DIRECTOR
        }
    }
}
