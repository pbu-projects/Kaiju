package lol.pbu.kaiju.security

import com.nimbusds.jwt.JWT
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import io.micronaut.security.token.RolesFinder
import io.micronaut.security.token.config.TokenConfiguration
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.service.UserService
import spock.lang.Specification

import java.text.ParseException

@MicronautTest(transactional = true)
class KaijuJwtAuthenticationFactorySpec extends Specification {

    @Inject
    TokenConfiguration tokenConfiguration

    @Inject
    RolesFinder rolesFinder

    @Inject
    UserService userService

    @Inject
    UserRepository userRepository

    KaijuJwtAuthenticationFactory factory

    def setup() {
        factory = new KaijuJwtAuthenticationFactory(tokenConfiguration, rolesFinder, userService)
    }

    def "createAuthentication | should successfully authenticate and map permissions when valid email claim is present"() {
        given: "a JWT containing an email claim"
        String email = "jwt.test.user@kaiju.local"
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject("auth-subject-123")
                .claim("email", email)
                .claim("email_verified", true)
                .claim("custom_attribute", "custom_val")
                .build()
        JWT token = new PlainJWT(claimsSet)

        when: "the factory creates authentication"
        def authOpt = factory.createAuthentication(token)

        then: "authentication is present"
        authOpt.isPresent()
        def auth = authOpt.get()

        and: "the principal name is the database user UUID"
        Optional<User> userOpt = userRepository.findByEmail(email)
        userOpt.isPresent()
        auth.name == userOpt.get().id().toString()

        and: "roles include all standard capability permissions"
        userOpt.get().role().permissions.each { permission ->
            assert auth.roles.contains(permission.claim)
        }

        and: "attributes preserve incoming token claims"
        auth.attributes.get("custom_attribute") == "custom_val"
    }

    def "createAuthentication | should canonicalize email to lowercase and trim whitespace before user lookup"() {
        given: "a JWT containing an uppercase email with whitespace"
        String rawEmail = "  CANONICAL.USER@Kaiju.Local  "
        String expectedEmail = "canonical.user@kaiju.local"
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("email", rawEmail)
                .claim("email_verified", true)
                .build()
        JWT token = new PlainJWT(claimsSet)

        when: "the factory creates authentication"
        def authOpt = factory.createAuthentication(token)

        then: "authentication is created under the normalized lowercase email"
        authOpt.isPresent()
        Optional<User> userOpt = userRepository.findByEmail(expectedEmail)
        userOpt.isPresent()
        authOpt.get().name == userOpt.get().id().toString()
    }

    def "createAuthentication | should return empty when email claim is missing"() {
        given: "a JWT with no email claim"
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject("legacy-subject-456")
                .claim("roles", ["STANDARD_USER"])
                .build()
        JWT token = new PlainJWT(claimsSet)

        when: "the factory creates authentication"
        def authOpt = factory.createAuthentication(token)

        then: "authentication is rejected with empty optional"
        authOpt.isEmpty()
    }

    def "createAuthentication | should return empty when email claim is blank or non-string"() {
        given: "a JWT with a blank email claim"
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject("blank-email-user")
                .claim("email", "   ")
                .build()
        JWT token = new PlainJWT(claimsSet)

        when: "the factory creates authentication"
        def authOpt = factory.createAuthentication(token)

        then: "authentication is rejected with empty optional"
        authOpt.isEmpty()
    }

    def "createAuthentication | should return empty when email_verified claim is missing, false, or invalid"() {
        given: "a JWT with unverified or missing email_verified claim"
        def builder = new JWTClaimsSet.Builder()
                .subject("unverified-user")
                .claim("email", "unverified@kaiju.local")
        if (verifiedClaim != "MISSING") {
            builder.claim("email_verified", verifiedClaim)
        }
        JWT token = new PlainJWT(builder.build())

        when: "the factory creates authentication"
        def authOpt = factory.createAuthentication(token)

        then: "authentication is rejected with empty optional"
        authOpt.isEmpty()

        where:
        verifiedClaim << ["MISSING", null, false, "false", "FALSE", "unverified", "no", 0]
    }

    def "createAuthentication | should return empty when database lookup fails gracefully"() {
        given: "a factory with a user service that throws an exception"
        UserService failingUserService = Mock(UserService)
        failingUserService.provisionOrGetUser(_ as String) >> { throw new RuntimeException("DB connection error") }
        KaijuJwtAuthenticationFactory failingFactory = new KaijuJwtAuthenticationFactory(tokenConfiguration, rolesFinder, failingUserService)

        and: "a JWT with a valid email claim"
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("email", "transient.failure@kaiju.local")
                .claim("email_verified", true)
                .build()
        JWT token = new PlainJWT(claimsSet)

        when: "the factory creates authentication"
        def authOpt = failingFactory.createAuthentication(token)

        then: "empty optional is returned without throwing exception"
        authOpt.isEmpty()
    }

    def "createAuthentication | should return empty when claims set is null"() {
        given: "a mock JWT returning null claims set"
        JWT token = Mock(JWT)
        token.getJWTClaimsSet() >> null

        when: "the factory creates authentication"
        def authOpt = factory.createAuthentication(token)

        then: "an empty optional is returned"
        authOpt.isEmpty()
    }

    def "createAuthentication | should return empty when ParseException is thrown"() {
        given: "a mock JWT throwing ParseException"
        JWT token = Mock(JWT)
        token.getJWTClaimsSet() >> { throw new ParseException("Malformed token claims", 0) }

        when: "the factory creates authentication"
        def authOpt = factory.createAuthentication(token)

        then: "an empty optional is returned without raising an uncaught exception"
        authOpt.isEmpty()
    }
}
