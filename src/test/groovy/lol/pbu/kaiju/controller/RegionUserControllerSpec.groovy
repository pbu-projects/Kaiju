package lol.pbu.kaiju.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.RegionUser
import lol.pbu.kaiju.domain.RegionUserId
import lol.pbu.kaiju.dto.CreateRegionUserRequest
import lol.pbu.kaiju.dto.UpdateRegionUserRequest
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.RegionUserRole
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.RegionUserRepository
import spock.lang.Unroll

import java.util.UUID

@MicronautTest(transactional = false)
class RegionUserControllerSpec extends BaseControllerSpec {

    private static final String BASE_PATH = "/region-users"

    @Inject
    RegionUserRepository regionUserRepository

    @Inject
    RegionUserController regionUserController

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid region user"() {
        given: "a new region user connection"
        def user = dataBuilder.user().asStandardUser().withEmail("regionuser@example.com").persist()
        def region = dataBuilder.region().withName("Test Region for User").persist()
        UUID userId = user.id()
        UUID regionId = region.id()

        def request = new CreateRegionUserRequest(userId, regionId, RegionUserRole.REGION_DIRECTOR)

        when: "the region user is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/region-users", request)), RegionUser)
        RegionUser saved = track(response.body())

        then: "200 OK is returned and record is persisted"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id().userId() == userId
            saved.id().regionId() == regionId
            saved.role() == RegionUserRole.REGION_DIRECTOR
        }

        and: "it can be retrieved from the database"
        def result = regionUserRepository.findById(saved.id()).orElse(null)
        verifyAll(result) {
            result != null
            role() == RegionUserRole.REGION_DIRECTOR
        }
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
        client.exchange(HttpRequest.POST(BASE_PATH, new CreateRegionUserRequest(UUID.randomUUID(), UUID.randomUUID(), RegionUserRole.REGION_DIRECTOR)))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "CREATE | should reject standard user attempting POST /region-users with 403 FORBIDDEN"() {
        given: "a region user creation request"
        def request = new CreateRegionUserRequest(UUID.randomUUID(), UUID.randomUUID(), RegionUserRole.REGION_AGENT)

        when: "a standard user attempts to create a region user"
        client.exchange(asStandardUser(HttpRequest.POST(BASE_PATH, request)), RegionUser)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "CREATE | should reject regional agent without region:manage claim attempting POST /region-users with 403 FORBIDDEN"() {
        given: "a region user creation request"
        def request = new CreateRegionUserRequest(UUID.randomUUID(), UUID.randomUUID(), RegionUserRole.REGION_AGENT)

        when: "a regional agent without region:manage attempts to create a region user"
        client.exchange(asRegionAgent(HttpRequest.POST(BASE_PATH, request)), RegionUser)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "CREATE | should allow regional director with region:manage claim to save a region user"() {
        given: "a target user, administrative region, and regional director assigned to that region"
        def targetUser = dataBuilder.user().asStandardUser().withEmail("director-create-user@example.com").persist()
        def targetRegion = dataBuilder.region().withName("Director Test Region Create").persist()
        def director = dataBuilder.user().withRole(UserRole.REGION_DIRECTOR).withEmail("director-create-actor@example.com").persist()
        UUID targetUserId = targetUser.id()
        UUID targetRegionId = targetRegion.id()
        UUID directorId = director.id()
        dataBuilder.regionUser().asRegionDirector(directorId, targetRegionId).persist()

        def request = new CreateRegionUserRequest(targetUserId, targetRegionId, RegionUserRole.REGION_AGENT)

        when: "the regional director creates the region user via HTTP POST"
        def response = client.exchange(asRegionDirector(HttpRequest.POST(BASE_PATH, request), directorId.toString()), RegionUser)
        RegionUser saved = track(response.body())

        then: "200 OK is returned and record is persisted"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id().userId() == targetUserId
            saved.id().regionId() == targetRegionId
            saved.role() == RegionUserRole.REGION_AGENT
        }

        and: "it can be retrieved from the database"
        def result = regionUserRepository.findById(saved.id()).orElse(null)
        verifyAll(result) {
            result != null
            role() == RegionUserRole.REGION_AGENT
        }
    }

    def "CREATE | should reject regional director assigned to a different region with 403 FORBIDDEN"() {
        given: "a target user, target region, and director assigned to another region"
        def targetUser = dataBuilder.user().asStandardUser().withEmail("cross-region-user@example.com").persist()
        def targetRegion = dataBuilder.region().withName("Cross Region Target").persist()
        def otherRegion = dataBuilder.region().withName("Director Assigned Region").persist()
        def director = dataBuilder.user().withRole(UserRole.REGION_DIRECTOR).withEmail("cross-region-director@example.com").persist()
        dataBuilder.regionUser().asRegionDirector(director.id(), otherRegion.id()).persist()

        def request = new CreateRegionUserRequest(targetUser.id(), targetRegion.id(), RegionUserRole.REGION_AGENT)

        when: "the director attempts to add a user to the unassigned target region"
        client.exchange(asRegionDirector(HttpRequest.POST(BASE_PATH, request), director.id().toString()), RegionUser)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing region user by composite ID"() {
        given: "an existing user and region"
        def user = dataBuilder.user().asStandardUser().withEmail("regionuser-read@example.com").persist()
        def region = dataBuilder.region().withName("Test Region for User Read").persist()
        UUID userId = user.id()
        UUID regionId = region.id()
        dataBuilder.regionUser().asRegionDirector(userId, regionId).persist()

        when: "the region user is requested via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/region-users/${userId}/${regionId}")), RegionUser)
        RegionUser result = response.body()

        then: "200 OK is returned with correct data"
        verifyAll {
            response.status == HttpStatus.OK
            result.id().userId() == userId
            result.id().regionId() == regionId
            result.role() == RegionUserRole.REGION_DIRECTOR
        }
    }

    def "READ | should allow user to view their own region membership"() {
        given: "a region agent assigned to Denver County"
        UUID userId = TestPersonas.CONNIE_COUNTY_AGENT.id
        UUID regionId = TestPersonas.DENVER_COUNTY_REGION_ID

        when: "the agent requests their own region membership via HTTP GET"
        def response = client.exchange(
                asRegionAgent(HttpRequest.GET("${BASE_PATH}/${userId}/${regionId}"), TestPersonas.CONNIE_COUNTY_AGENT),
                RegionUser
        )
        RegionUser result = response.body()

        then: "200 OK is returned with her membership"
        verifyAll {
            response.status == HttpStatus.OK
            result.id().userId() == userId
            result.id().regionId() == regionId
            result.role() == RegionUserRole.REGION_AGENT
        }
    }

    def "READ | should reject outsider, agent, and cross-region director viewing another user's region membership with 403 FORBIDDEN"() {
        given: "a region user membership in Denver County"
        UUID targetUserId = TestPersonas.DANIEL_COUNTY_DIRECTOR.id
        UUID regionId = TestPersonas.DENVER_COUNTY_REGION_ID

        when: "a standard user attempts to read the membership"
        client.exchange(
                asStandardUser(HttpRequest.GET("${BASE_PATH}/${targetUserId}/${regionId}"), TestPersonas.VICTOR_VOLUNTEER),
                RegionUser
        )

        then: "403 Forbidden is thrown"
        def volErr = thrown(HttpClientResponseException)
        volErr.status == HttpStatus.FORBIDDEN

        when: "a region agent in the same region attempts to read the director's membership"
        client.exchange(
                asRegionAgent(HttpRequest.GET("${BASE_PATH}/${targetUserId}/${regionId}"), TestPersonas.CONNIE_COUNTY_AGENT),
                RegionUser
        )

        then: "403 Forbidden is thrown"
        def agentErr = thrown(HttpClientResponseException)
        agentErr.status == HttpStatus.FORBIDDEN

        when: "a director from El Paso County attempts to read the Denver County membership"
        client.exchange(
                asRegionDirector(HttpRequest.GET("${BASE_PATH}/${targetUserId}/${regionId}"), TestPersonas.ETHAN_SIBLING_DIRECTOR),
                RegionUser
        )

        then: "403 Forbidden is thrown"
        def crossRegionErr = thrown(HttpClientResponseException)
        crossRegionErr.status == HttpStatus.FORBIDDEN
    }

    def "READ | should reject demoted standard user with stale REGION_DIRECTOR row viewing another user's region membership with 403 FORBIDDEN"() {
        given: "a region with a demoted STANDARD_USER who still has a REGION_DIRECTOR row and a target member"
        def demotedUser = dataBuilder.user().asStandardUser().withEmail("demoted-region-dir@example.com").persist()
        def targetUser = dataBuilder.user().asStandardUser().withEmail("target-region-member@example.com").persist()
        def region = dataBuilder.region().withName("Stale Region Director Test").persist()
        dataBuilder.regionUser().asRegionDirector(demotedUser.id(), region.id()).persist()
        dataBuilder.regionUser().asRegionAgent(targetUser.id(), region.id()).persist()

        when: "the demoted user requests the target user's region membership via HTTP GET"
        client.exchange(
                asStandardUser(HttpRequest.GET("${BASE_PATH}/${targetUser.id()}/${region.id()}"), demotedUser.id().toString()),
                RegionUser
        )

        then: "403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
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
        def user = dataBuilder.user().asStandardUser().withEmail("regionuser-update@example.com").persist()
        def region = dataBuilder.region().withName("Test Region for User Update").persist()
        UUID userId = user.id()
        UUID regionId = region.id()
        dataBuilder.regionUser().asRegionAgent(userId, regionId).persist()

        def request = new UpdateRegionUserRequest(RegionUserRole.REGION_DIRECTOR)

        when: "the region user is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/region-users/${userId}/${regionId}", request)), RegionUser)
        RegionUser updated = response.body()

        then: "the update is reflected in the response"
        verifyAll {
            response.status == HttpStatus.OK
            updated.role() == RegionUserRole.REGION_DIRECTOR
        }

        and: "persisted in the database"
        def role = regionUserRepository.findById(new RegionUserId(userId, regionId)).orElse(null)?.role()
        role == RegionUserRole.REGION_DIRECTOR
    }

    def "UPDATE | should fail to update a non-existent region user"() {
        given: "a non-existent composite ID and request"
        def request = new UpdateRegionUserRequest(RegionUserRole.REGION_DIRECTOR)

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/region-users/${UUID.randomUUID()}/${UUID.randomUUID()}", request)), RegionUser)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "UPDATE | should reject unauthenticated PUT /region-users/{userId}/{regionId} with 401 UNAUTHORIZED"() {
        given: "an update request"
        def request = new UpdateRegionUserRequest(RegionUserRole.REGION_DIRECTOR)

        when: "an unauthenticated caller attempts to update a region user"
        client.exchange(HttpRequest.PUT("${BASE_PATH}/${UUID.randomUUID()}/${UUID.randomUUID()}", request))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "UPDATE | should reject standard user attempting PUT /region-users/{userId}/{regionId} with 403 FORBIDDEN"() {
        given: "an update request"
        def request = new UpdateRegionUserRequest(RegionUserRole.REGION_DIRECTOR)

        when: "a standard user attempts to update a region user"
        client.exchange(asStandardUser(HttpRequest.PUT("${BASE_PATH}/${UUID.randomUUID()}/${UUID.randomUUID()}", request)), RegionUser)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should reject regional agent without region:manage claim attempting PUT /region-users/{userId}/{regionId} with 403 FORBIDDEN"() {
        given: "an update request"
        def request = new UpdateRegionUserRequest(RegionUserRole.REGION_DIRECTOR)

        when: "a regional agent without region:manage attempts to update a region user"
        client.exchange(asRegionAgent(HttpRequest.PUT("${BASE_PATH}/${UUID.randomUUID()}/${UUID.randomUUID()}", request)), RegionUser)

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should reject cross-region director attempting PUT /region-users/{userId}/{regionId} with 403 FORBIDDEN"() {
        given: "a Denver County membership and an El Paso County director"
        UUID targetUserId = TestPersonas.CONNIE_COUNTY_AGENT.id
        UUID regionId = TestPersonas.DENVER_COUNTY_REGION_ID
        def request = new UpdateRegionUserRequest(RegionUserRole.REGION_DIRECTOR)

        when: "the El Paso County director attempts to update the Denver County membership"
        client.exchange(
                asRegionDirector(HttpRequest.PUT("${BASE_PATH}/${targetUserId}/${regionId}", request), TestPersonas.ETHAN_SIBLING_DIRECTOR),
                RegionUser
        )

        then: "403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should allow regional director with region:manage claim to update an existing region user role"() {
        given: "an existing region user association and regional director assigned to that region"
        def targetUser = dataBuilder.user().asStandardUser().withEmail("director-update-user@example.com").persist()
        def targetRegion = dataBuilder.region().withName("Director Test Region Update").persist()
        def director = dataBuilder.user().withRole(UserRole.REGION_DIRECTOR).withEmail("director-update-actor@example.com").persist()
        UUID targetUserId = targetUser.id()
        UUID targetRegionId = targetRegion.id()
        UUID directorId = director.id()
        dataBuilder.regionUser().asRegionAgent(targetUserId, targetRegionId).persist()
        dataBuilder.regionUser().asRegionDirector(directorId, targetRegionId).persist()

        def request = new UpdateRegionUserRequest(RegionUserRole.REGION_DIRECTOR)

        when: "the regional director updates the role via HTTP PUT"
        def response = client.exchange(asRegionDirector(HttpRequest.PUT("${BASE_PATH}/${targetUserId}/${targetRegionId}", request), directorId.toString()), RegionUser)
        RegionUser updated = response.body()

        then: "200 OK is returned and update is reflected in response"
        verifyAll {
            response.status == HttpStatus.OK
            updated.role() == RegionUserRole.REGION_DIRECTOR
        }

        and: "persisted in the database"
        def role = regionUserRepository.findById(new RegionUserId(targetUserId, targetRegionId)).orElse(null)?.role()
        role == RegionUserRole.REGION_DIRECTOR
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing region user"() {
        given: "an existing region user"
        def user = dataBuilder.user().asStandardUser().withEmail("regionuser-delete@example.com").persist()
        def region = dataBuilder.region().withName("Test Region for User Delete").persist()
        UUID userId = user.id()
        UUID regionId = region.id()
        dataBuilder.regionUser().asRegionAgent(userId, regionId).persist()

        when: "the region user is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/region-users/${userId}/${regionId}")))

        then: "200 OK is returned and it no longer exists in repository"
        verifyAll {
            response.status == HttpStatus.OK
            !regionUserRepository.existsById(new RegionUserId(userId, regionId))
        }
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

    def "DELETE | should reject cross-region director attempting DELETE /region-users/{userId}/{regionId} with 403 FORBIDDEN"() {
        given: "a Denver County membership and an El Paso County director"
        UUID targetUserId = TestPersonas.CONNIE_COUNTY_AGENT.id
        UUID regionId = TestPersonas.DENVER_COUNTY_REGION_ID

        when: "the El Paso County director attempts to delete the Denver County membership"
        client.exchange(
                asRegionDirector(HttpRequest.DELETE("${BASE_PATH}/${targetUserId}/${regionId}"), TestPersonas.ETHAN_SIBLING_DIRECTOR)
        )

        then: "403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should allow regional director with region:manage claim to remove an existing region user"() {
        given: "an existing region user association and regional director assigned to that region"
        def targetUser = dataBuilder.user().asStandardUser().withEmail("director-del-user@example.com").persist()
        def targetRegion = dataBuilder.region().withName("Director Test Region Delete").persist()
        def director = dataBuilder.user().withRole(UserRole.REGION_DIRECTOR).withEmail("director-del-actor@example.com").persist()
        UUID targetUserId = targetUser.id()
        UUID targetRegionId = targetRegion.id()
        UUID directorId = director.id()
        dataBuilder.regionUser().asRegionAgent(targetUserId, targetRegionId).persist()
        dataBuilder.regionUser().asRegionDirector(directorId, targetRegionId).persist()

        when: "the regional director deletes the region user via HTTP DELETE"
        def response = client.exchange(asRegionDirector(HttpRequest.DELETE("${BASE_PATH}/${targetUserId}/${targetRegionId}"), directorId.toString()))

        then: "200 OK is returned and record is removed from the repository"
        verifyAll {
            response.status == HttpStatus.OK
            !regionUserRepository.existsById(new RegionUserId(targetUserId, targetRegionId))
        }
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve region users with pagination"() {
        when: "requesting region users via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/region-users?size=5")), Map)

        then: "the response is 200 OK with content list"
        Map body = response.body()
        verifyAll {
            response.status == HttpStatus.OK
            body.content instanceof List
        }
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
        def totalCount = regionUserRepository.count()
        allRegionUsers.size() == totalCount
    }
}
