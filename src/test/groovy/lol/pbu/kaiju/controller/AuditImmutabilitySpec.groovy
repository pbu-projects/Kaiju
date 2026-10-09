package lol.pbu.kaiju.controller

import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import spock.lang.Unroll

import java.util.UUID

@MicronautTest(transactional = false)
class AuditImmutabilitySpec extends BaseControllerSpec {

    @Unroll
    def "AUDIT IMMUTABILITY | #method #endpoint should be rejected (404 or 405)"(String method, HttpMethod httpMethod, String endpoint) {
        when: "attempting a forbidden mutation on audit log endpoints"
        def request = asGlobalAdmin(
                HttpRequest.create(httpMethod, endpoint)
                        .contentType(MediaType.APPLICATION_JSON_TYPE)
                        .body("{}")
        )

        client.exchange(request)

        then: "the server rejects the mutation because endpoints are deleted"
        def e = thrown(HttpClientResponseException)
        e.status in [HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND]

        where:
        method   | httpMethod        | endpoint
        "PUT"    | HttpMethod.PUT    | "/project-audit-logs/${UUID.randomUUID()}"
        "DELETE" | HttpMethod.DELETE | "/project-audit-logs/${UUID.randomUUID()}"
        "POST"   | HttpMethod.POST   | "/project-audit-logs"
        "PUT"    | HttpMethod.PUT    | "/organization-audit-logs/${UUID.randomUUID()}"
        "DELETE" | HttpMethod.DELETE | "/organization-audit-logs/${UUID.randomUUID()}"
        "POST"   | HttpMethod.POST   | "/organization-audit-logs"
    }
}
