package lol.pbu.kaiju.security

import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdAuthenticationMapper
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.service.UserService
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@MicronautTest(transactional = true)
class AuthentikAuthenticationMapperSpec extends Specification {

    @Inject
    UserRepository userRepository

    @Inject
    UserService userService

    @Inject
    IdpRoleMapper idpRoleMapper

    @Inject
    AuthentikAuthenticationMapper mapper

    private static <T> T await(Publisher<T> publisher) {
        CompletableFuture<T> future = new CompletableFuture<>()
        publisher.subscribe(new Subscriber<T>() {
            @Override
            void onSubscribe(Subscription s) {
                s.request(1)
            }
            @Override
            void onNext(T t) {
                future.complete(t)
            }
            @Override
            void onError(Throwable t) {
                future.completeExceptionally(t)
            }
            @Override
            void onComplete() {
                if (!future.isDone()) {
                    future.complete(null)
                }
            }
        })
        return future.get(5, TimeUnit.SECONDS)
    }

    def "should return failure if email is missing from claims"() {
        given: "a fake incoming token with no email"
        OpenIdClaims claims = [getEmail: { -> null }, get: { String k -> k == "email_verified" ? true : null }] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "it refuses to authenticate without an email"
        verifyAll(response) {
            !isAuthenticated()
            getMessage().get() == "No email present in OpenID claims"
        }
    }

    def "should return failure if email is blank in claims"() {
        given: "a fake incoming token with blank email"
        OpenIdClaims claims = [getEmail: { -> "   " }, get: { String k -> k == "email_verified" ? true : null }] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "it refuses to authenticate with a blank email"
        verifyAll(response) {
            !isAuthenticated()
            getMessage().get() == "No email present in OpenID claims"
        }
    }

    @Unroll
    def "should return failure if email_verified claim is '#verifiedValue'"() {
        given: "an incoming token with unverified or missing email_verified claim"
        OpenIdClaims claims = [
                getEmail: { -> "unverified@example.com" },
                get: { String k -> k == "email_verified" ? verifiedValue : null }
        ] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication fails with unverified email message"
        verifyAll(response) {
            !isAuthenticated()
            getMessage().get() == "Email is not verified in OpenID claims"
        }

        where:
        verifiedValue << [null, false, "false", "no", 123]
    }

    def "should lookup existing user and return authentication response with db role and permissions"() {
        given: "a real user saved in the actual PostGIS database"
        String email = "existing-${UUID.randomUUID()}@example.com"
        User existingUser = userRepository.save(new User(null, email, UserRole.GLOBAL_ADMIN, OffsetDateTime.now()))

        and: "an incoming token for that email with verified email"
        OpenIdClaims claims = [
                getEmail: { -> email },
                get: { String k -> k == "email_verified" ? true : null }
        ] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "it logs them in with their actual database role and permissions"
        verifyAll(response) {
            isAuthenticated()
            getAuthentication().get().getName() == existingUser.id().toString()
            getAuthentication().get().getRoles().contains("GLOBAL_ADMIN")
            getAuthentication().get().getRoles().contains("system:admin")
            getAuthentication().get().getAttributes().get("email") == email
        }
    }

    def "should provision new user if they do not exist in the database"() {
        given: "an email that does NOT exist in the database"
        String email = "new-${UUID.randomUUID()}@example.com"
        assert !userRepository.findByEmail(email).isPresent()

        and: "an incoming token for that email with verified email"
        OpenIdClaims claims = [
                getEmail: { -> email },
                get: { String k -> k == "email_verified" ? true : null }
        ] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "the authentication is successful, matches default role, and persists to database"
        def dbUser = userRepository.findByEmail(email).get()
        verifyAll {
            response.isAuthenticated()
            response.getAuthentication().get().getRoles().contains("STANDARD_USER")
            response.getAuthentication().get().getRoles().contains("project:create")
            response.getAuthentication().get().getAttributes().get("email") == email
            dbUser.email() == email
            dbUser.role() == UserRole.STANDARD_USER
            response.getAuthentication().get().getName() == dbUser.id().toString()
        }
    }

    def "should delegate to UserService to provision or get user"() {
        given: "a mock UserService"
        UserService mockUserService = Mock()
        String email = "delegated-${UUID.randomUUID()}@example.com"
        User mockUser = new User(UUID.randomUUID(), email, UserRole.STANDARD_USER, OffsetDateTime.now())

        and: "a mapper using the mock UserService"
        AuthentikAuthenticationMapper testMapper = new AuthentikAuthenticationMapper(mockUserService, idpRoleMapper)
        OpenIdClaims claims = [
                getEmail: { -> email },
                get: { String k -> k == "email_verified" ? true : null }
        ] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the login"
        def publisher = testMapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication succeeds with user from UserService"
        1 * mockUserService.provisionOrGetUser(email, UserRole.STANDARD_USER) >> mockUser
        verifyAll(response) {
            isAuthenticated()
            getAuthentication().get().getName() == mockUser.id().toString()
            getAuthentication().get().getRoles().contains("STANDARD_USER")
            getAuthentication().get().getAttributes().get("email") == email
        }
    }

    def "should canonicalize email to lowercase trimmed string"() {
        given: "a mock UserService"
        UserService mockUserService = Mock()
        String rawEmail = "  User.MixEdCase@Example.COM  "
        String canonicalEmail = "user.mixedcase@example.com"
        User mockUser = new User(UUID.randomUUID(), canonicalEmail, UserRole.STANDARD_USER, OffsetDateTime.now())

        and: "a mapper using the mock UserService"
        AuthentikAuthenticationMapper testMapper = new AuthentikAuthenticationMapper(mockUserService, idpRoleMapper)
        OpenIdClaims claims = [
                getEmail: { -> rawEmail },
                get: { String k -> k == "email_verified" ? true : null }
        ] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the login"
        def publisher = testMapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "provisionOrGetUser is invoked with the canonical lowercase trimmed email"
        1 * mockUserService.provisionOrGetUser(canonicalEmail, UserRole.STANDARD_USER) >> mockUser
        response.isAuthenticated()
    }

    @Unroll
    def "should provision new user with role '#expectedRole' derived from upstream IdP group '#groupClaim'"() {
        given: "an unseeded email"
        String email = "idp-role-${UUID.randomUUID()}@example.com"
        assert !userRepository.findByEmail(email).isPresent()

        and: "an incoming token for that email with groups claim"
        OpenIdClaims claims = [
                getEmail: { -> email },
                get: { String k ->
                    if (k == "email_verified") return true
                    if (k == "groups") return groupClaim
                    return null
                }
        ] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication succeeds with expected role and permissions"
        def dbUser = userRepository.findByEmail(email).get()
        verifyAll {
            response.isAuthenticated()
            response.getAuthentication().get().getRoles().contains(expectedRole.name())
            dbUser.role() == expectedRole
        }

        where:
        groupClaim                                                              | expectedRole
        ["Kaiju Global Admins"]                                                 | UserRole.GLOBAL_ADMIN
        ["Kaiju Region Directors"]                                              | UserRole.REGION_DIRECTOR
        ["Kaiju Region Agents"]                                                 | UserRole.REGION_AGENT
        ["Kaiju Org Admins"]                                                    | UserRole.STANDARD_USER
        ["Kaiju Standard Users"]                                                | UserRole.STANDARD_USER
        []                                                                      | UserRole.STANDARD_USER
        null                                                                    | UserRole.STANDARD_USER
        ["Kaiju Region Agents", "Kaiju Global Admins"]                          | UserRole.GLOBAL_ADMIN
        ["Kaiju Region Agents", "Kaiju Region Directors"]                       | UserRole.REGION_DIRECTOR
        "Kaiju Global Admins"                                                   | UserRole.GLOBAL_ADMIN
        (["Kaiju Region Directors"] as String[])                                | UserRole.REGION_DIRECTOR
    }

    def "should maintain database role as authoritative when existing user logs in with different IdP group"() {
        given: "an existing user with REGION_DIRECTOR role in database"
        String email = "existing-director-${UUID.randomUUID()}@example.com"
        User existingUser = userRepository.save(new User(null, email, UserRole.REGION_DIRECTOR, OffsetDateTime.now()))

        and: "an incoming token with different IdP group"
        OpenIdClaims claims = [
                getEmail: { -> email },
                get: { String k ->
                    if (k == "email_verified") return true
                    if (k == "groups") return ["Kaiju Global Admins"]
                    return null
                }
        ] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication succeeds using the authoritative database role"
        verifyAll(response) {
            isAuthenticated()
            getAuthentication().get().getRoles().contains("REGION_DIRECTOR")
            !getAuthentication().get().getRoles().contains("GLOBAL_ADMIN")
        }
    }

    def "should include openIdToken and email_verified in authentication attributes when id token is present"() {
        given: "an incoming token with id token and verified email"
        String email = "token-${UUID.randomUUID()}@example.com"
        String rawIdToken = "header.payload.signature"
        OpenIdClaims claims = [
                getEmail: { -> email },
                get: { String k -> k == "email_verified" ? true : null }
        ] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()
        token.setIdToken(rawIdToken)

        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "attributes include email, email_verified, and openIdToken"
        verifyAll(response) {
            isAuthenticated()
            getAuthentication().isPresent()
            getAuthentication().get().getAttributes().get("email") == email
            getAuthentication().get().getAttributes().get("email_verified") == true
            getAuthentication().get().getAttributes().get(OpenIdAuthenticationMapper.OPENID_TOKEN_KEY) == rawIdToken
        }
    }
}
