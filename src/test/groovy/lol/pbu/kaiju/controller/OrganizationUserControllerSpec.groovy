package lol.pbu.kaiju.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.OrganizationUser
import lol.pbu.kaiju.domain.OrganizationUserId
import lol.pbu.kaiju.dto.CreateOrganizationUserRequest
import lol.pbu.kaiju.dto.UpdateOrganizationUserRequest
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.OrganizationUserRole
import lol.pbu.kaiju.repository.OrganizationUserRepository
import spock.lang.Unroll

import java.util.UUID

@MicronautTest(transactional = false)
class OrganizationUserControllerSpec extends BaseControllerSpec {

    @Inject
    OrganizationUserRepository organizationUserRepository

    @Inject
    OrganizationUserController organizationUserController

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid organization user when called by org admin"() {
        given: "a new organization user connection and an org admin"
        UUID userId = TestPersonas.VICTOR_VOLUNTEER.id
        UUID orgId = TestPersonas.DENVER_METRO_BRANCH_ID

        def request = new CreateOrganizationUserRequest(userId, orgId, OrganizationUserRole.ORG_ADMIN)

        when: "the organization user is added by org admin via HTTP POST"
        def response = client.exchange(
                asOrgAdmin(HttpRequest.POST("/organization-users", request), TestPersonas.LEO_LOCAL_ADMIN),
                OrganizationUser
        )
        OrganizationUser saved = track(response.body())

        then: "200 OK is returned and record is persisted"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id().userId() == userId
            saved.id().organizationId() == orgId
            saved.role() == OrganizationUserRole.ORG_ADMIN
        }

        and: "it can be retrieved from the database"
        def result = organizationUserRepository.findById(saved.id()).orElse(null)
        verifyAll(result) {
            result != null
            role() == OrganizationUserRole.ORG_ADMIN
        }
    }

    def "CREATE | should throw 403 Forbidden when standard user attempts to add org user"() {
        given: "an unauthorized standard user"
        UUID orgId = TestPersonas.DENVER_METRO_BRANCH_ID
        UUID targetUserId = TestPersonas.VALERIE_VOLUNTEER.id

        def request = new CreateOrganizationUserRequest(targetUserId, orgId, OrganizationUserRole.ORG_ADMIN)

        when: "the standard user attempts to add org user via HTTP POST"
        client.exchange(
                asVolunteer(HttpRequest.POST("/organization-users", request), TestPersonas.VICTOR_VOLUNTEER),
                OrganizationUser
        )

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "CREATE | should reject cross-org admin attempting to add user to another organization with 403 FORBIDDEN"() {
        given: "an org admin of Denver Metro Branch targeting Utah State Chapter"
        def request = new CreateOrganizationUserRequest(
                TestPersonas.VICTOR_VOLUNTEER.id,
                TestPersonas.UTAH_STATE_CHAPTER_ID,
                OrganizationUserRole.ORG_MEMBER
        )

        when: "the Denver org admin attempts to add a member to Utah State Chapter"
        client.exchange(
                asOrgAdmin(HttpRequest.POST("/organization-users", request), TestPersonas.LEO_LOCAL_ADMIN),
                OrganizationUser
        )

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    @Unroll
    def "CREATE | should fail to save organization user with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/organization-users", payload)), OrganizationUser)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase            | payload
        "Null User ID"      | [userId: null, organizationId: UUID.randomUUID(), role: "ORG_ADMIN"]
        "Null Org ID"       | [userId: UUID.randomUUID(), organizationId: null, role: "ORG_ADMIN"]
        "Null Role"         | [userId: UUID.randomUUID(), organizationId: UUID.randomUUID(), role: null]
    }

    def "CREATE | should reject unauthenticated POST /organization-users with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to add org user"
        client.exchange(HttpRequest.POST("/organization-users", new CreateOrganizationUserRequest(UUID.randomUUID(), UUID.randomUUID(), OrganizationUserRole.ORG_ADMIN)))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing organization user by composite ID"() {
        given: "an existing user and organization"
        UUID userId = TestPersonas.LEO_LOCAL_ADMIN.id
        UUID orgId = TestPersonas.DENVER_METRO_BRANCH_ID

        when: "the organization user is requested via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organization-users/${userId}/${orgId}")), OrganizationUser)
        OrganizationUser result = response.body()

        then: "200 OK is returned with the correct record"
        verifyAll {
            response.status == HttpStatus.OK
            result.id().userId() == userId
            result.id().organizationId() == orgId
            result.role() == OrganizationUserRole.ORG_ADMIN
        }
    }

    def "READ | should allow user to view their own organization membership"() {
        given: "a member of Denver Metro Branch"
        UUID userId = TestPersonas.LUKE_LOCAL_MEMBER.id
        UUID orgId = TestPersonas.DENVER_METRO_BRANCH_ID

        when: "the member reads their own membership record"
        def response = client.exchange(
                asOrgMember(HttpRequest.GET("/organization-users/${userId}/${orgId}"), TestPersonas.LUKE_LOCAL_MEMBER),
                OrganizationUser
        )
        OrganizationUser result = response.body()

        then: "200 OK is returned with their membership details"
        verifyAll {
            response.status == HttpStatus.OK
            result.id().userId() == userId
            result.id().organizationId() == orgId
            result.role() == OrganizationUserRole.ORG_MEMBER
        }
    }

    def "READ | should reject outsider and cross-org admin viewing another user's organization membership with 403 FORBIDDEN"() {
        given: "a membership in Denver Metro Branch"
        UUID targetUserId = TestPersonas.LEO_LOCAL_ADMIN.id
        UUID orgId = TestPersonas.DENVER_METRO_BRANCH_ID

        when: "an unaffiliated volunteer requests the membership"
        client.exchange(
                asVolunteer(HttpRequest.GET("/organization-users/${targetUserId}/${orgId}"), TestPersonas.VICTOR_VOLUNTEER),
                OrganizationUser
        )

        then: "403 Forbidden is thrown"
        def outsiderErr = thrown(HttpClientResponseException)
        outsiderErr.status == HttpStatus.FORBIDDEN

        when: "an org admin from Utah State Chapter requests the Denver membership"
        client.exchange(
                asOrgAdmin(HttpRequest.GET("/organization-users/${targetUserId}/${orgId}"), TestPersonas.URSULA_UTAH_ADMIN),
                OrganizationUser
        )

        then: "403 Forbidden is thrown"
        def crossOrgErr = thrown(HttpClientResponseException)
        crossOrgErr.status == HttpStatus.FORBIDDEN
    }

    def "READ | should return 404 for a non-existent composite ID"() {
        when: "a non-existent organization user is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/organization-users/${UUID.randomUUID()}/${UUID.randomUUID()}")), OrganizationUser)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /organization-users/{userId}/{orgId} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to read an org user"
        client.exchange(HttpRequest.GET("/organization-users/${UUID.randomUUID()}/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED status is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing organization user role when called by org admin"() {
        given: "an existing organization user and an org admin"
        UUID userId = TestPersonas.LUKE_LOCAL_MEMBER.id
        UUID orgId = TestPersonas.DENVER_METRO_BRANCH_ID

        def request = new UpdateOrganizationUserRequest(OrganizationUserRole.ORG_ADMIN)

        when: "the organization user is updated by org admin via HTTP PUT"
        def response = client.exchange(
                asOrgAdmin(HttpRequest.PUT("/organization-users/${userId}/${orgId}", request), TestPersonas.LEO_LOCAL_ADMIN),
                OrganizationUser
        )
        OrganizationUser updated = response.body()

        then: "the update is reflected in the response"
        verifyAll {
            response.status == HttpStatus.OK
            updated.role() == OrganizationUserRole.ORG_ADMIN
        }

        and: "persisted in the database"
        def role = organizationUserRepository.findById(new OrganizationUserId(userId, orgId)).orElse(null)?.role()
        role == OrganizationUserRole.ORG_ADMIN

        cleanup:
        organizationUserRepository.update(dataBuilder.organizationUser().asOrgMember(userId, orgId).build())
    }

    def "UPDATE | should throw 403 Forbidden when standard user attempts to update org user"() {
        given: "an unauthorized standard user"
        UUID userId = TestPersonas.LUKE_LOCAL_MEMBER.id
        UUID orgId = TestPersonas.DENVER_METRO_BRANCH_ID

        def request = new UpdateOrganizationUserRequest(OrganizationUserRole.ORG_ADMIN)

        when: "the standard user attempts to update org user via HTTP PUT"
        client.exchange(
                asVolunteer(HttpRequest.PUT("/organization-users/${userId}/${orgId}", request), TestPersonas.VICTOR_VOLUNTEER),
                OrganizationUser
        )

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should throw 403 Forbidden when cross-org admin attempts to update org user in another organization"() {
        given: "a Denver org admin targeting a Utah State Chapter membership"
        UUID userId = TestPersonas.URSULA_UTAH_ADMIN.id
        UUID orgId = TestPersonas.UTAH_STATE_CHAPTER_ID
        def request = new UpdateOrganizationUserRequest(OrganizationUserRole.ORG_MEMBER)

        when: "the Denver org admin attempts to update the Utah membership"
        client.exchange(
                asOrgAdmin(HttpRequest.PUT("/organization-users/${userId}/${orgId}", request), TestPersonas.LEO_LOCAL_ADMIN),
                OrganizationUser
        )

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should fail to update a non-existent organization user when called by admin"() {
        given: "a non-existent user and organization ID"
        def nonExistentUserId = UUID.randomUUID()
        def nonExistentOrgId = UUID.randomUUID()
        def request = new UpdateOrganizationUserRequest(OrganizationUserRole.ORG_ADMIN)

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/organization-users/${nonExistentUserId}/${nonExistentOrgId}", request)), OrganizationUser)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "UPDATE | should reject unauthenticated PUT /organization-users/{userId}/{orgId} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to update an organization user"
        client.exchange(HttpRequest.PUT("/organization-users/${UUID.randomUUID()}/${UUID.randomUUID()}", new UpdateOrganizationUserRequest(OrganizationUserRole.ORG_ADMIN)))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing organization user when called by org admin"() {
        given: "an existing organization user and an org admin"
        UUID userId = TestPersonas.VALERIE_VOLUNTEER.id
        UUID orgId = TestPersonas.DENVER_METRO_BRANCH_ID
        dataBuilder.organizationUser().asOrgMember(userId, orgId).persist()

        when: "the organization user is deleted by org admin via HTTP DELETE"
        def response = client.exchange(
                asOrgAdmin(HttpRequest.DELETE("/organization-users/${userId}/${orgId}"), TestPersonas.LEO_LOCAL_ADMIN)
        )

        then: "200 OK is returned and record is removed from the database"
        verifyAll {
            response.status == HttpStatus.OK
            !organizationUserRepository.findById(new OrganizationUserId(userId, orgId)).isPresent()
        }
    }

    def "DELETE | should throw 403 Forbidden when standard user attempts to delete org user"() {
        given: "an unauthorized standard user"
        UUID targetUserId = TestPersonas.LUKE_LOCAL_MEMBER.id
        UUID orgId = TestPersonas.DENVER_METRO_BRANCH_ID

        when: "the standard user attempts to delete org user via HTTP DELETE"
        client.exchange(
                asVolunteer(HttpRequest.DELETE("/organization-users/${targetUserId}/${orgId}"), TestPersonas.VICTOR_VOLUNTEER)
        )

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should throw 403 Forbidden when cross-org admin attempts to delete org user in another organization"() {
        given: "a Denver org admin targeting a Utah State Chapter membership"
        UUID targetUserId = TestPersonas.URSULA_UTAH_ADMIN.id
        UUID orgId = TestPersonas.UTAH_STATE_CHAPTER_ID

        when: "the Denver org admin attempts to delete the Utah membership"
        client.exchange(
                asOrgAdmin(HttpRequest.DELETE("/organization-users/${targetUserId}/${orgId}"), TestPersonas.LEO_LOCAL_ADMIN)
        )

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should fail to delete a non-existent organization user when called by admin"() {
        given: "random non-existent user and organization IDs"
        def nonExistentUserId = UUID.randomUUID()
        def nonExistentOrgId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/organization-users/${nonExistentUserId}/${nonExistentOrgId}")))

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "DELETE | should reject unauthenticated DELETE /organization-users/{userId}/{orgId} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to delete an organization user"
        client.exchange(HttpRequest.DELETE("/organization-users/${UUID.randomUUID()}/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve organization users with pagination"() {
        when: "requesting organization users via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organization-users?size=5")), Map)

        then: "the response is 200 OK with content list"
        Map body = response.body()
        verifyAll {
            response.status == HttpStatus.OK
            body.content instanceof List
        }
    }

    def "LIST | should fully drain all organization users sequentially using cursors"() {
        setup:
        Set<OrganizationUser> allOrgUsers = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("id.userId")))

        when: "iterating through pages"
        while (pageable != null) {
            CursoredPage<OrganizationUser> page = organizationUserController.getOrganizationUsers(pageable)
            allOrgUsers.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected size matches the DB count"
        def totalCount = organizationUserRepository.count()
        allOrgUsers.size() == totalCount
    }
}
