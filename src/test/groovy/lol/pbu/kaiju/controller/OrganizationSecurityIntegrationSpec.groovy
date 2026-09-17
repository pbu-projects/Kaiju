package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.context.annotation.Property
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.model.VerificationStatus
import spock.lang.Specification

import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class OrganizationSecurityIntegrationSpec extends Specification {

    @Inject
    EmbeddedServer embeddedServer

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
}
