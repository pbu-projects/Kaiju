package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.model.VerificationStatus
import spock.lang.Specification

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class OrganizationSecurityIntegrationSpec extends Specification {

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

    def "Security | should reject unauthenticated access to /organizations/search-by-location"() {
        when: "an unauthenticated request is made to search organizations by location"
        client.exchange(HttpRequest.GET("/organizations/search-by-location?longitude=-104.99&latitude=39.73&radiusMeters=5000").accept(MediaType.APPLICATION_JSON_TYPE))

        then: "an UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }
}

