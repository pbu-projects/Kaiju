package lol.pbu.kaiju.security

import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository
import spock.lang.Specification

import java.time.OffsetDateTime
import java.util.UUID

@MicronautTest(transactional = true)
class AuthentikAuthenticationMapperSpec extends Specification {

    @Inject
    UserRepository userRepository
    
    @Inject
    AuthentikAuthenticationMapper mapper

    def "should return failure if email is missing from claims"() {
        given: "a fake incoming token with no email"
        // We use a Map proxy here to represent the external Authentik claims JSON
        OpenIdClaims claims = [getEmail: { -> null }] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()
        
        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = reactor.core.publisher.Mono.from(publisher).block()

        then: "it refuses to authenticate without an email"
        !response.isAuthenticated()
        response.getMessage().get() == "No email present in OpenID claims"
    }

    def "should lookup existing user and return authentication response with db role"() {
        given: "a real user saved in the actual PostGIS database"
        String email = "existing-${UUID.randomUUID()}@example.com"
        User existingUser = userRepository.save(new User(null, email, UserRole.GLOBAL_ADMIN, OffsetDateTime.now()))
        
        and: "an incoming token for that email"
        OpenIdClaims claims = [getEmail: { -> email }] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()
        
        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = reactor.core.publisher.Mono.from(publisher).block()

        then: "it logs them in with their actual database role"
        response.isAuthenticated()
        response.getAuthentication().get().getName() == existingUser.id().toString()
        response.getAuthentication().get().getRoles().contains("GLOBAL_ADMIN")
        response.getAuthentication().get().getAttributes().get("email") == email
    }

    def "should provision new user if they do not exist in the database"() {
        given: "an email that does NOT exist in the database"
        String email = "new-${UUID.randomUUID()}@example.com"
        assert !userRepository.findByEmail(email).isPresent()
        
        and: "an incoming token for that email"
        OpenIdClaims claims = [getEmail: { -> email }] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()
        
        when: "the mapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = reactor.core.publisher.Mono.from(publisher).block()

        then: "the authentication is successful and assigned the default role"
        response.isAuthenticated()
        response.getAuthentication().get().getRoles().contains("STANDARD_USER")
        response.getAuthentication().get().getAttributes().get("email") == email
        
        and: "the user was actually physically persisted into the real PostGIS database"
        def dbUser = userRepository.findByEmail(email).get()
        dbUser.email() == email
        dbUser.role() == UserRole.STANDARD_USER
        
        and: "their new database UUID was used as the session ID"
        response.getAuthentication().get().getName() == dbUser.id().toString()
    }
}
