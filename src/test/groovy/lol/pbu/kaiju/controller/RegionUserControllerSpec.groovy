package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.RegionUser
import lol.pbu.kaiju.domain.RegionUserId
import lol.pbu.kaiju.dto.CreateRegionUserCommand
import lol.pbu.kaiju.dto.UpdateRegionUserCommand
import lol.pbu.kaiju.model.RegionUserRole
import lol.pbu.kaiju.repository.RegionUserRepository
import spock.lang.Unroll

import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class RegionUserControllerSpec extends BaseControllerSpec {

    private static final String ROLE_STANDARD_USER = "STANDARD_USER"
    private static final String ROLE_REGION_AGENT = "REGION_AGENT"
    private static final String ROLE_REGION_DIRECTOR = "REGION_DIRECTOR"
    private static final String CLAIM_REGION_MANAGE = "region:manage"
    private static final String BASE_PATH = "/region-users"

    @Inject
    RegionUserRepository regionUserRepository

    @Inject
    RegionUserController regionUserController

    protected <T> MutableHttpRequest<T> asStandardUser(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, [ROLE_STANDARD_USER])
    }

    protected <T> MutableHttpRequest<T> asRegionAgent(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, [ROLE_REGION_AGENT])
    }

    protected <T> MutableHttpRequest<T> asRegionDirector(MutableHttpRequest<T> request, String userId = UUID.randomUUID().toString()) {
        authenticated(request, userId, [ROLE_REGION_DIRECTOR, CLAIM_REGION_MANAGE])
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid region user"() {
        given: "a new region user connection"
        UUID userId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "regionuser@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", regionId, "Test Region for User")

        def command = new CreateRegionUserCommand(userId, regionId, RegionUserRole.REGION_DIRECTOR)

        when: "the region user is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/region-users", command)), RegionUser)
        RegionUser saved = response.body()

        then: "200 OK is returned and record is persisted"
        response.status == HttpStatus.OK
        saved.id().userId() == userId
        saved.id().regionId() == regionId
        saved.role() == RegionUserRole.REGION_DIRECTOR

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT role FROM region_users WHERE user_id = ? AND region_id = ?", [userId, regionId])
        result.role == 'REGION_DIRECTOR'

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", userId, regionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", regionId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    @Unroll
    def "CREATE | should fail to save region user with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/region-users", payload)), RegionUser)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase        | payload
        "Null User ID"  | [userId: null, regionId: UUID.randomUUID(), role: "REGION_DIRECTOR"]
        "Null Region ID"| [userId: UUID.randomUUID(), regionId: null, role: "REGION_DIRECTOR"]
        "Null Role"     | [userId: UUID.randomUUID(), regionId: UUID.randomUUID(), role: null]
    }

    def "CREATE | should reject unauthenticated POST /region-users with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to add a region user"
        client.exchange(HttpRequest.POST(BASE_PATH, new CreateRegionUserCommand(UUID.randomUUID(), UUID.randomUUID(), RegionUserRole.REGION_DIRECTOR)))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "CREATE | should reject standard user attempting POST /region-users with 403 FORBIDDEN"() {
        given: "a region user creation command"
        def command = new CreateRegionUserCommand(UUID.randomUUID(), UUID.randomUUID(), RegionUserRole.REGION_AGENT)

        when: "a standard user attempts to create a region user"
        client.exchange(asStandardUser(HttpRequest.POST(BASE_PATH, command)), RegionUser)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "CREATE | should reject regional agent without region:manage claim attempting POST /region-users with 403 FORBIDDEN"() {
        given: "a region user creation command"
        def command = new CreateRegionUserCommand(UUID.randomUUID(), UUID.randomUUID(), RegionUserRole.REGION_AGENT)

        when: "a regional agent without region:manage attempts to create a region user"
        client.exchange(asRegionAgent(HttpRequest.POST(BASE_PATH, command)), RegionUser)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "CREATE | should allow regional director with region:manage claim to save a region user"() {
        given: "a target user, administrative region, and regional director"
        UUID targetUserId = UUID.randomUUID()
        UUID targetRegionId = UUID.randomUUID()
        UUID directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", targetUserId, "director-create-user@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", targetRegionId, "Director Test Region Create")

        def command = new CreateRegionUserCommand(targetUserId, targetRegionId, RegionUserRole.REGION_AGENT)

        when: "the regional director creates the region user via HTTP POST"
        def response = client.exchange(asRegionDirector(HttpRequest.POST(BASE_PATH, command), directorId.toString()), RegionUser)
        RegionUser saved = response.body()

        then: "200 OK is returned and record is persisted"
        response.status == HttpStatus.OK
        saved.id().userId() == targetUserId
        saved.id().regionId() == targetRegionId
        saved.role() == RegionUserRole.REGION_AGENT

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT role FROM region_users WHERE user_id = ? AND region_id = ?", [targetUserId, targetRegionId])
        result.role == 'REGION_AGENT'

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", targetUserId, targetRegionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", targetRegionId)
        executeUpdate("DELETE FROM users WHERE id = ?", targetUserId)
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing region user by composite ID"() {
        given: "an existing user and region"
        UUID userId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "regionuser-read@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", regionId, "Test Region for User Read")
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", userId, regionId)

        when: "the region user is requested via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/region-users/${userId}/${regionId}")), RegionUser)
        RegionUser result = response.body()

        then: "200 OK is returned with correct data"
        response.status == HttpStatus.OK
        result.id().userId() == userId
        result.id().regionId() == regionId
        result.role() == RegionUserRole.REGION_DIRECTOR

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", userId, regionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", regionId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    def "READ | should return 404 for a non-existent composite ID"() {
        when: "a non-existent region user is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/region-users/${UUID.randomUUID()}/${UUID.randomUUID()}")), RegionUser)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /region-users/{userId}/{regionId} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to read a region user"
        client.exchange(HttpRequest.GET("/region-users/${UUID.randomUUID()}/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing region user role"() {
        given: "an existing region user"
        UUID userId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "regionuser-update@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", regionId, "Test Region for User Update")
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_AGENT')", userId, regionId)

        def command = new UpdateRegionUserCommand(RegionUserRole.REGION_DIRECTOR)

        when: "the region user is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/region-users/${userId}/${regionId}", command)), RegionUser)
        RegionUser updated = response.body()

        then: "the update is reflected in the response"
        response.status == HttpStatus.OK
        updated.role() == RegionUserRole.REGION_DIRECTOR

        and: "persisted in the database"
        def role = sql.firstRow("SELECT role FROM region_users WHERE user_id = ? AND region_id = ?", [userId, regionId]).role
        role == 'REGION_DIRECTOR'

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", userId, regionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", regionId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    def "UPDATE | should fail to update a non-existent region user"() {
        given: "a non-existent composite ID and command"
        def command = new UpdateRegionUserCommand(RegionUserRole.REGION_DIRECTOR)

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/region-users/${UUID.randomUUID()}/${UUID.randomUUID()}", command)), RegionUser)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "UPDATE | should reject unauthenticated PUT /region-users/{userId}/{regionId} with 401 UNAUTHORIZED"() {
        given: "an update command"
        def command = new UpdateRegionUserCommand(RegionUserRole.REGION_DIRECTOR)

        when: "an unauthenticated caller attempts to update a region user"
        client.exchange(HttpRequest.PUT("${BASE_PATH}/${UUID.randomUUID()}/${UUID.randomUUID()}", command))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "UPDATE | should reject standard user attempting PUT /region-users/{userId}/{regionId} with 403 FORBIDDEN"() {
        given: "an update command"
        def command = new UpdateRegionUserCommand(RegionUserRole.REGION_DIRECTOR)

        when: "a standard user attempts to update a region user"
        client.exchange(asStandardUser(HttpRequest.PUT("${BASE_PATH}/${UUID.randomUUID()}/${UUID.randomUUID()}", command)), RegionUser)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should reject regional agent without region:manage claim attempting PUT /region-users/{userId}/{regionId} with 403 FORBIDDEN"() {
        given: "an update command"
        def command = new UpdateRegionUserCommand(RegionUserRole.REGION_DIRECTOR)

        when: "a regional agent without region:manage attempts to update a region user"
        client.exchange(asRegionAgent(HttpRequest.PUT("${BASE_PATH}/${UUID.randomUUID()}/${UUID.randomUUID()}", command)), RegionUser)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should allow regional director with region:manage claim to update an existing region user role"() {
        given: "an existing region user association and regional director"
        UUID targetUserId = UUID.randomUUID()
        UUID targetRegionId = UUID.randomUUID()
        UUID directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", targetUserId, "director-update-user@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", targetRegionId, "Director Test Region Update")
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_AGENT')", targetUserId, targetRegionId)

        def command = new UpdateRegionUserCommand(RegionUserRole.REGION_DIRECTOR)

        when: "the regional director updates the role via HTTP PUT"
        def response = client.exchange(asRegionDirector(HttpRequest.PUT("${BASE_PATH}/${targetUserId}/${targetRegionId}", command), directorId.toString()), RegionUser)
        RegionUser updated = response.body()

        then: "200 OK is returned and update is reflected in response"
        response.status == HttpStatus.OK
        updated.role() == RegionUserRole.REGION_DIRECTOR

        and: "persisted in the database"
        def role = sql.firstRow("SELECT role FROM region_users WHERE user_id = ? AND region_id = ?", [targetUserId, targetRegionId]).role
        role == 'REGION_DIRECTOR'

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", targetUserId, targetRegionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", targetRegionId)
        executeUpdate("DELETE FROM users WHERE id = ?", targetUserId)
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing region user"() {
        given: "an existing region user"
        UUID userId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "regionuser-delete@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", regionId, "Test Region for User Delete")
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_AGENT')", userId, regionId)

        when: "the region user is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/region-users/${userId}/${regionId}")))

        then: "200 OK is returned"
        response.status == HttpStatus.OK

        and: "it no longer exists in repository"
        !regionUserRepository.existsById(new RegionUserId(userId, regionId))

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", userId, regionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", regionId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    def "DELETE | should fail to delete a non-existent region user"() {
        given: "random non-existent IDs"
        def nonExistentUserId = UUID.randomUUID()
        def nonExistentRegionId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/region-users/${nonExistentUserId}/${nonExistentRegionId}")))

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "DELETE | should reject unauthenticated DELETE /region-users/{userId}/{regionId} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to delete a region user"
        client.exchange(HttpRequest.DELETE("${BASE_PATH}/${UUID.randomUUID()}/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "DELETE | should reject standard user attempting DELETE /region-users/{userId}/{regionId} with 403 FORBIDDEN"() {
        when: "a standard user attempts to delete a region user"
        client.exchange(asStandardUser(HttpRequest.DELETE("${BASE_PATH}/${UUID.randomUUID()}/${UUID.randomUUID()}")))

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should reject regional agent without region:manage claim attempting DELETE /region-users/{userId}/{regionId} with 403 FORBIDDEN"() {
        when: "a regional agent without region:manage attempts to delete a region user"
        client.exchange(asRegionAgent(HttpRequest.DELETE("${BASE_PATH}/${UUID.randomUUID()}/${UUID.randomUUID()}")))

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should allow regional director with region:manage claim to remove an existing region user"() {
        given: "an existing region user association and regional director"
        UUID targetUserId = UUID.randomUUID()
        UUID targetRegionId = UUID.randomUUID()
        UUID directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", targetUserId, "director-del-user@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", targetRegionId, "Director Test Region Delete")
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_AGENT')", targetUserId, targetRegionId)

        when: "the regional director deletes the region user via HTTP DELETE"
        def response = client.exchange(asRegionDirector(HttpRequest.DELETE("${BASE_PATH}/${targetUserId}/${targetRegionId}"), directorId.toString()))

        then: "200 OK is returned"
        response.status == HttpStatus.OK

        and: "the record is removed from the repository"
        !regionUserRepository.existsById(new RegionUserId(targetUserId, targetRegionId))

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", targetUserId, targetRegionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", targetRegionId)
        executeUpdate("DELETE FROM users WHERE id = ?", targetUserId)
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve region users with pagination"() {
        when: "requesting region users via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/region-users?size=5")), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
    }

    def "LIST | should fully drain all region users sequentially using cursors"() {
        setup:
        Set<RegionUser> allRegionUsers = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("id.userId")))

        when: "iterating through pages"
        while (pageable != null) {
            CursoredPage<RegionUser> page = regionUserController.getRegionUsers(pageable)
            allRegionUsers.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected size matches the DB count"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM region_users").count
        allRegionUsers.size() == totalCount
    }
}
