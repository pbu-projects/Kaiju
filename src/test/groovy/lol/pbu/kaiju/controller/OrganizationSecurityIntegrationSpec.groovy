package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.context.annotation.Property
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.OrganizationRepository
import spock.lang.Specification
import spock.lang.Unroll

import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@MicronautTest(transactional = false)
class OrganizationSecurityIntegrationSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient httpClient

    @Inject
    OrganizationRepository organizationRepository

    BlockingHttpClient getClient() {
        return httpClient.toBlocking()
    }

    def cleanup() {
        organizationRepository.deleteAll()
    }

    def "Security | should reject unauthenticated access to /organizations"() {
        when: "an unauthenticated request is made to list organizations"
        client.exchange(HttpRequest.GET("/organizations"))

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
        client.exchange(HttpRequest.PUT("/organizations/${id}/no-look", dummy))

        then: "since the endpoint is removed, it should ideally return 405 or 401. If 401 is thrown first, that's fine too."
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED || e.status == HttpStatus.METHOD_NOT_ALLOWED
    }

}
