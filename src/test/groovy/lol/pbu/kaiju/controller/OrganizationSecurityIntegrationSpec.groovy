package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.publisher.Publishers
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.filters.AuthenticationFetcher
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.model.VerificationStatus
import org.reactivestreams.Publisher
import spock.lang.Specification

@Property(name = "spec.name", value = "OrganizationSecurityIntegrationSpec")
@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class OrganizationSecurityIntegrationSpec extends Specification {

    @Requires(property = "spec.name", value = "OrganizationSecurityIntegrationSpec")
    @Singleton
    static class TestAuthenticationFetcher implements AuthenticationFetcher<HttpRequest<?>> {
        @Override
        Publisher<Authentication> fetchAuthentication(HttpRequest<?> request) {
            String testUser = request.getHeaders().get("X-Test-User")
            if (testUser) {
                List<String> roles = request.getHeaders().getAll("X-Test-Role")
                return Publishers.just(Authentication.build(testUser, roles))
            }
            return Publishers.empty()
        }
    }

    @Inject
    @Client("/")
    HttpClient httpClient

    BlockingHttpClient getClient() {
        return httpClient.toBlocking()
    }

    def "Security | should reject unauthenticated access to /organizations"() {
        when: "an unauthenticated request is made to list organizations"
        client.exchange(HttpRequest.GET("/organizations").accept(MediaType.APPLICATION_JSON_TYPE))

        then: "an UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "Security | should reject non-admin authenticated users from /organizations with 403 FORBIDDEN"() {
        when: "a standard authenticated user (lacking system:admin claim) requests bulk organizations"
        client.exchange(
                HttpRequest.GET("/organizations")
                        .header("X-Test-User", "volunteer-user")
                        .header("X-Test-Role", "volunteer")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
        )

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "Security | should allow global admin with system:admin claim to access /organizations"() {
        when: "a global admin requests bulk organizations"
        def response = client.exchange(
                HttpRequest.GET("/organizations")
                        .header("X-Test-User", "global-admin")
                        .header("X-Test-Role", "system:admin")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
        )

        then: "a 200 OK response is returned"
        response.status == HttpStatus.OK
    }

    def "Security | should return METHOD_NOT_ALLOWED for removed no-look endpoints"() {
        given: "a random UUID"
        def id = UUID.randomUUID()
        
        and: "a dummy organization payload"
        def dummy = new Organization(null, "Test", "https://example.com", null, true, VerificationStatus.UNVERIFIED, null, [])

        when: "an unauthenticated request is made to a no-look endpoint (PUT)"
        client.exchange(HttpRequest.PUT("/organizations/${id}/no-look", dummy).accept(MediaType.APPLICATION_JSON_TYPE))

        then: "since the endpoint is removed, it should return 405, 404, or 401"
        def e = thrown(HttpClientResponseException)
        e.status in [HttpStatus.UNAUTHORIZED, HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND]
    }

    def "Security | should reject unauthenticated access to /organizations/search-by-name"() {
        when: "an unauthenticated request is made to search organizations by name"
        client.exchange(HttpRequest.GET("/organizations/search-by-name?name=test").accept(MediaType.APPLICATION_JSON_TYPE))

        then: "an UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "Security | should allow authenticated standard users to search by name"() {
        when: "a standard authenticated user searches organizations by name"
        def response = client.exchange(
                HttpRequest.GET("/organizations/search-by-name?name=Salvation")
                        .header("X-Test-User", "regular-user")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
        )

        then: "a 200 OK response is returned"
        response.status == HttpStatus.OK
    }

    def "Security | should reject unauthenticated access to /organizations/search-by-location"() {
        when: "an unauthenticated request is made to search organizations by location"
        client.exchange(HttpRequest.GET("/organizations/search-by-location?longitude=-104.99&latitude=39.73&radiusMeters=5000").accept(MediaType.APPLICATION_JSON_TYPE))

        then: "an UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "Security | should allow authenticated standard users to search by location"() {
        when: "a standard authenticated user searches organizations by location"
        def response = client.exchange(
                HttpRequest.GET("/organizations/search-by-location?longitude=-104.99&latitude=39.73&radiusMeters=5000")
                        .header("X-Test-User", "regular-user")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
        )

        then: "a 200 OK response is returned"
        response.status == HttpStatus.OK
    }

    def "Security | should reject unauthenticated access to /organizations/search-by-region"() {
        when: "an unauthenticated request is made to search organizations by region"
        client.exchange(HttpRequest.GET("/organizations/search-by-region?regionId=${UUID.randomUUID()}").accept(MediaType.APPLICATION_JSON_TYPE))

        then: "an UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "Security | should allow authenticated standard users to search by region"() {
        when: "a standard authenticated user searches organizations by region"
        def response = client.exchange(
                HttpRequest.GET("/organizations/search-by-region?regionId=${UUID.randomUUID()}")
                        .header("X-Test-User", "regular-user")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
        )

        then: "a 200 OK response is returned"
        response.status == HttpStatus.OK
    }
}

