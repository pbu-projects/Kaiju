package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Tag
import lol.pbu.kaiju.repository.TagRepository

@MicronautTest(transactional = false)
class AtomicDeletionSpec extends BaseControllerSpec {

    @Inject
    @Client("/")
    HttpClient httpClient

    @Inject
    TagRepository tagRepository

    def cleanup() {
        cleanupDatabase()
    }

    def "DELETE | should return 404 NOT_FOUND atomically when resource does not exist"() {
        given: "a random UUID that does not exist in the database"
        def nonExistentId = UUID.randomUUID()

        when: "attempting to delete the resource via HTTP DELETE"
        httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/tags/${nonExistentId}")
                        .header("X-Test-User", "admin-user")
                        .header("X-Test-Role", "system:admin")
        )

        then: "a 404 NOT_FOUND exception is returned directly without TOCTOU race"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND

        cleanup:
        cleanupDatabase()
    }

    def "DELETE | should return 200 or 204 and remove record atomically when resource exists"() {
        given: "a persisted tag entity"
        def tag = tagRepository.save(new Tag(null, "atomic-test-${UUID.randomUUID()}"))

        when: "deleting the tag via HTTP DELETE"
        def response = httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/tags/${tag.id()}")
                        .header("X-Test-User", "admin-user")
                        .header("X-Test-Role", "system:admin")
        )

        then: "response indicates success"
        response.status in [HttpStatus.OK, HttpStatus.NO_CONTENT]

        and: "the record is removed from the database"
        !tagRepository.findById(tag.id()).isPresent()

        cleanup:
        cleanupDatabase()
    }
}
