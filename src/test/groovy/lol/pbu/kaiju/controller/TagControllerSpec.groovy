package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Tag
import lol.pbu.kaiju.dto.CreateTagCommand
import lol.pbu.kaiju.dto.UpdateTagCommand
import lol.pbu.kaiju.repository.TagRepository
import net.datafaker.Faker
import spock.lang.Shared
import spock.lang.Unroll

import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class TagControllerSpec extends BaseControllerSpec {

    private static final String ROLE_STANDARD_USER = "STANDARD_USER"
    private static final String ROLE_REGION_AGENT = "REGION_AGENT"
    private static final String ROLE_REGION_DIRECTOR = "REGION_DIRECTOR"
    private static final String CLAIM_REGION_MANAGE = "region:manage"
    private static final String BASE_PATH = "/tags"

    @Inject
    TagRepository tagRepository

    @Shared
    Faker faker = new Faker()

    protected <T> MutableHttpRequest<T> asStandardUser(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, [ROLE_STANDARD_USER])
    }

    protected <T> MutableHttpRequest<T> asRegionAgent(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, [ROLE_REGION_AGENT])
    }

    protected <T> MutableHttpRequest<T> asRegionDirector(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, [ROLE_REGION_DIRECTOR, CLAIM_REGION_MANAGE])
    }

    def setup() {
        sql.execute("INSERT INTO tags (name) VALUES ('test-tag-a')")
        sql.execute("INSERT INTO tags (name) VALUES ('test-tag-b')")
    }

    def cleanup() {
        sql.execute("DELETE FROM tags WHERE name LIKE 'test-tag-%' OR name LIKE 'tag-%' OR name LIKE 'updated-%' OR name LIKE 'temporary-tag-%' OR name LIKE 'director-%'")
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid tag"() {
        given: "a valid create tag command"
        String tagName = "tag-${faker.lorem().word()}-${UUID.randomUUID().toString().substring(0, 8)}"
        def command = new CreateTagCommand(tagName)

        when: "the tag is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/tags", command)), Tag)
        Tag saved = response.body()

        then: "the tag is persisted with 200 OK and generated ID"
        response.status == HttpStatus.OK
        saved.id() != null
        saved.name() == tagName

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM tags WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == name
        }
    }

    @Unroll
    def "CREATE | should fail to save tag with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add a tag with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/tags", payload)), Tag)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase        | payload
        "Null Name"     | [name: null]
        "Blank Name"    | [name: "   "]
        "Name Too Long" | [name: "A" * 51]
    }

    def "CREATE | should reject unauthenticated POST /tags with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to create a tag"
        client.exchange(HttpRequest.POST("/tags", new CreateTagCommand("unauth-tag")), Tag)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "CREATE | should allow regional director with region:manage claim to create a tag"() {
        given: "a valid create tag command"
        String tagName = "director-tag-${UUID.randomUUID().toString().substring(0, 8)}"
        def command = new CreateTagCommand(tagName)

        when: "the tag is added by a regional director via HTTP POST"
        def response = client.exchange(asRegionDirector(HttpRequest.POST(BASE_PATH, command)), Tag)
        Tag saved = response.body()

        then: "the tag is persisted with 200 OK and generated ID"
        response.status == HttpStatus.OK
        saved.id() != null
        saved.name() == tagName

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM tags WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == name
        }

        cleanup:
        if (saved?.id()) {
            sql.execute("DELETE FROM tags WHERE id = ?", [saved.id()])
        }
    }

    def "CREATE | should reject standard user attempting POST /tags with 403 FORBIDDEN"() {
        given: "a valid tag command"
        def command = new CreateTagCommand("standard-user-tag")

        when: "a standard user attempts to create a tag via HTTP POST"
        client.exchange(asStandardUser(HttpRequest.POST(BASE_PATH, command)), Tag)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "CREATE | should reject regional agent without region:manage claim attempting POST /tags with 403 FORBIDDEN"() {
        given: "a valid tag command"
        def command = new CreateTagCommand("agent-tag")

        when: "a regional agent attempts to create a tag via HTTP POST"
        client.exchange(asRegionAgent(HttpRequest.POST(BASE_PATH, command)), Tag)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing tag by ID"() {
        given: "an existing tag"
        def tag = tagRepository.save(new Tag(null, "test-tag-read-${faker.number().digits(5)}"))
        UUID id = tag.id()

        when: "the tag is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/tags/${id}")), Tag)

        then: "200 OK is returned with the correct tag"
        response.status == HttpStatus.OK
        response.body().id() == id
        response.body().name() == tag.name()
    }

    def "READ | should return 404 for a non-existent tag ID"() {
        when: "a non-existent tag is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/tags/${UUID.randomUUID()}")), Tag)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /tags/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to get a tag"
        client.exchange(HttpRequest.GET("/tags/${UUID.randomUUID()}"), Tag)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing tag"() {
        given: "an existing tag"
        def tag = tagRepository.save(new Tag(null, "original-tag-${faker.number().digits(5)}"))
        UUID id = tag.id()
        def newName = "updated-${faker.lorem().word()}-${UUID.randomUUID().toString().substring(0, 8)}"
        def updateCommand = new UpdateTagCommand(newName)

        when: "the tag is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/tags/${id}", updateCommand)), Tag)
        Tag updated = response.body()

        then: "200 OK is returned with updated data"
        response.status == HttpStatus.OK
        updated.id() == id
        updated.name() == newName

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT name FROM tags WHERE id = ?", [id])
        verifyAll(dbResult) {
            name == newName
        }
    }

    def "UPDATE | should fail to update a non-existent tag"() {
        given: "a random non-existent ID and an update command"
        def nonExistentId = UUID.randomUUID()
        def updateCommand = new UpdateTagCommand("new-tag")

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/tags/${nonExistentId}", updateCommand)), Tag)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "UPDATE | should fail to update tag with invalid data: #testCase"(String testCase, Map payload) {
        given: "an existing tag"
        def tag = tagRepository.save(new Tag(null, "tag-for-invalid-update"))
        UUID id = tag.id()

        when: "an update with invalid data is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/tags/${id}", payload)), Tag)

        then: "a 400 BAD REQUEST status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase        | payload
        "Blank Name"    | [name: "   "]
        "Name Too Long" | [name: "A" * 51]
    }

    def "UPDATE | should reject unauthenticated PUT /tags/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to update a tag"
        client.exchange(HttpRequest.PUT("/tags/${UUID.randomUUID()}", new UpdateTagCommand("test")), Tag)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "UPDATE | should allow regional director with region:manage claim to update an existing tag"() {
        given: "an existing tag"
        def tag = tagRepository.save(new Tag(null, "original-tag-${faker.number().digits(5)}"))
        UUID id = tag.id()
        def newName = "director-updated-${UUID.randomUUID().toString().substring(0, 8)}"
        def updateCommand = new UpdateTagCommand(newName)

        when: "the tag is updated by a regional director via HTTP PUT"
        def response = client.exchange(asRegionDirector(HttpRequest.PUT("${BASE_PATH}/${id}", updateCommand)), Tag)
        Tag updated = response.body()

        then: "200 OK is returned with updated data"
        response.status == HttpStatus.OK
        updated.id() == id
        updated.name() == newName

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT name FROM tags WHERE id = ?", [id])
        verifyAll(dbResult) {
            name == newName
        }

        cleanup:
        sql.execute("DELETE FROM tags WHERE id = ?", [id])
    }

    def "UPDATE | should reject standard user attempting PUT /tags/{id} with 403 FORBIDDEN"() {
        given: "an update command and target tag ID"
        def updateCommand = new UpdateTagCommand("unauthorized-update")
        UUID id = UUID.randomUUID()

        when: "a standard user attempts to update a tag via HTTP PUT"
        client.exchange(asStandardUser(HttpRequest.PUT("${BASE_PATH}/${id}", updateCommand)), Tag)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should reject regional agent without region:manage claim attempting PUT /tags/{id} with 403 FORBIDDEN"() {
        given: "an update command and target tag ID"
        def updateCommand = new UpdateTagCommand("unauthorized-update")
        UUID id = UUID.randomUUID()

        when: "a regional agent attempts to update a tag via HTTP PUT"
        client.exchange(asRegionAgent(HttpRequest.PUT("${BASE_PATH}/${id}", updateCommand)), Tag)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing tag"() {
        given: "a new tag to be deleted"
        def saved = tagRepository.save(new Tag(null, "temporary-tag-to-delete"))
        UUID id = saved.id()
        assert tagRepository.existsById(id)

        when: "the tag is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/tags/${id}")))

        then: "the response is 200 OK"
        response.status == HttpStatus.OK

        and: "the tag no longer exists in the repository or database"
        verifyAll {
            !tagRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM tags WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent tag"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/tags/${nonExistentId}")))

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "DELETE | should reject unauthenticated DELETE /tags/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to delete a tag"
        client.exchange(HttpRequest.DELETE("/tags/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "DELETE | should allow regional director with region:manage claim to remove an existing tag"() {
        given: "a new tag to be deleted"
        def saved = tagRepository.save(new Tag(null, "director-delete-tag"))
        UUID id = saved.id()
        assert tagRepository.existsById(id)

        when: "the tag is deleted by a regional director via HTTP DELETE"
        def response = client.exchange(asRegionDirector(HttpRequest.DELETE("${BASE_PATH}/${id}")))

        then: "the response is 200 OK"
        response.status == HttpStatus.OK

        and: "the tag no longer exists in the repository or database"
        verifyAll {
            !tagRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM tags WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should reject standard user attempting DELETE /tags/{id} with 403 FORBIDDEN"() {
        when: "a standard user attempts to delete a tag via HTTP DELETE"
        client.exchange(asStandardUser(HttpRequest.DELETE("${BASE_PATH}/${UUID.randomUUID()}")))

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should reject regional agent without region:manage claim attempting DELETE /tags/{id} with 403 FORBIDDEN"() {
        when: "a regional agent attempts to delete a tag via HTTP DELETE"
        client.exchange(asRegionAgent(HttpRequest.DELETE("${BASE_PATH}/${UUID.randomUUID()}")))

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve tags with pagination"() {
        when: "requesting tags via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/tags?size=5")), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
        body.content.size() >= 2
    }

    def "LIST | should reject unauthenticated GET /tags with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to list tags"
        client.exchange(HttpRequest.GET("/tags"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }
}
