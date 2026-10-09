package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
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
import lol.pbu.kaiju.dto.CreateOrganizationUserCommand
import lol.pbu.kaiju.dto.UpdateOrganizationUserCommand
import lol.pbu.kaiju.model.OrganizationUserRole
import lol.pbu.kaiju.repository.OrganizationUserRepository
import spock.lang.Unroll

import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class OrganizationUserControllerSpec extends BaseControllerSpec {

    @Inject
    OrganizationUserRepository organizationUserRepository

    @Inject
    OrganizationUserController organizationUserController

    UUID adminId = UUID.randomUUID()

    def setup() {
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, 'admin-ou@example.com', 'GLOBAL_ADMIN') ON CONFLICT DO NOTHING", adminId)
    }

    def cleanup() {
        executeUpdate("DELETE FROM users WHERE id = ?", adminId)
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid organization user when called by org admin"() {
        given: "a new organization user connection and an org admin"
        UUID orgAdminId = UUID.randomUUID()
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", orgAdminId, "orgadmin@example.com")
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "orguser@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Test Org for User")
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_ADMIN')", orgAdminId, orgId)

        def command = new CreateOrganizationUserCommand(userId, orgId, OrganizationUserRole.ORG_ADMIN)

        when: "the organization user is added by org admin via HTTP POST"
        def response = client.exchange(
                authenticated(HttpRequest.POST("/organization-users", command), orgAdminId.toString(), ["STANDARD_USER"]),
                OrganizationUser
        )
        OrganizationUser saved = response.body()

        then: "200 OK is returned and record is persisted"
        response.status == HttpStatus.OK
        saved.id().userId() == userId
        saved.id().organizationId() == orgId
        saved.role() == OrganizationUserRole.ORG_ADMIN

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT role FROM organization_users WHERE user_id = ? AND organization_id = ?", [userId, orgId])
        result.role == 'ORG_ADMIN'

        cleanup:
        executeUpdate("DELETE FROM organization_users WHERE user_id = ? AND organization_id = ?", userId, orgId)
        executeUpdate("DELETE FROM organization_users WHERE user_id = ? AND organization_id = ?", orgAdminId, orgId)
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", userId, orgAdminId)
    }

    def "CREATE | should throw 403 Forbidden when standard user attempts to add org user"() {
        given: "an unauthorized standard user"
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        UUID targetUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "attacker-org@example.com")
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", targetUserId, "target-org@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Target Org")

        def command = new CreateOrganizationUserCommand(targetUserId, orgId, OrganizationUserRole.ORG_ADMIN)

        when: "the standard user attempts to add org user via HTTP POST"
        client.exchange(
                authenticated(HttpRequest.POST("/organization-users", command), userId.toString(), ["STANDARD_USER"]),
                OrganizationUser
        )

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        cleanup:
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", userId, targetUserId)
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
        client.exchange(HttpRequest.POST("/organization-users", new CreateOrganizationUserCommand(UUID.randomUUID(), UUID.randomUUID(), OrganizationUserRole.ORG_ADMIN)))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing organization user by composite ID"() {
        given: "an existing user and organization"
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "orguser-read@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Test Org for User Read")
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_ADMIN')", userId, orgId)

        when: "the organization user is requested via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organization-users/${userId}/${orgId}")), OrganizationUser)
        OrganizationUser result = response.body()

        then: "200 OK is returned with the correct record"
        response.status == HttpStatus.OK
        result.id().userId() == userId
        result.id().organizationId() == orgId
        result.role() == OrganizationUserRole.ORG_ADMIN

        cleanup:
        executeUpdate("DELETE FROM organization_users WHERE user_id = ? AND organization_id = ?", userId, orgId)
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
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
        UUID orgAdminId = UUID.randomUUID()
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", orgAdminId, "orgadmin-upd@example.com")
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "orguser-update@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Test Org for User Update")
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_ADMIN')", orgAdminId, orgId)
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MEMBER')", userId, orgId)

        def command = new UpdateOrganizationUserCommand(OrganizationUserRole.ORG_ADMIN)

        when: "the organization user is updated by org admin via HTTP PUT"
        def response = client.exchange(
                authenticated(HttpRequest.PUT("/organization-users/${userId}/${orgId}", command), orgAdminId.toString(), ["STANDARD_USER"]),
                OrganizationUser
        )
        OrganizationUser updated = response.body()

        then: "the update is reflected in the response"
        response.status == HttpStatus.OK
        updated.role() == OrganizationUserRole.ORG_ADMIN

        and: "persisted in the database"
        def role = sql.firstRow("SELECT role FROM organization_users WHERE user_id = ? AND organization_id = ?", [userId, orgId]).role
        role == 'ORG_ADMIN'

        cleanup:
        executeUpdate("DELETE FROM organization_users WHERE user_id IN (?, ?) AND organization_id = ?", userId, orgAdminId, orgId)
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", userId, orgAdminId)
    }

    def "UPDATE | should throw 403 Forbidden when standard user attempts to update org user"() {
        given: "an unauthorized standard user"
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        UUID targetUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "attacker-upd@example.com")
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", targetUserId, "target-upd@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Target Org Upd")

        def command = new UpdateOrganizationUserCommand(OrganizationUserRole.ORG_ADMIN)

        when: "the standard user attempts to update org user via HTTP PUT"
        client.exchange(
                authenticated(HttpRequest.PUT("/organization-users/${targetUserId}/${orgId}", command), userId.toString(), ["STANDARD_USER"]),
                OrganizationUser
        )

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        cleanup:
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", userId, targetUserId)
    }

    def "UPDATE | should fail to update a non-existent organization user when called by admin"() {
        given: "a non-existent user and organization ID"
        def nonExistentUserId = UUID.randomUUID()
        def nonExistentOrgId = UUID.randomUUID()
        def command = new UpdateOrganizationUserCommand(OrganizationUserRole.ORG_ADMIN)

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/organization-users/${nonExistentUserId}/${nonExistentOrgId}", command), adminId.toString()), OrganizationUser)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing organization user when called by org admin"() {
        given: "an existing organization user and an org admin"
        UUID orgAdminId = UUID.randomUUID()
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", orgAdminId, "orgadmin-del@example.com")
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "orguser-delete@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Test Org for User Delete")
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_ADMIN')", orgAdminId, orgId)
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MEMBER')", userId, orgId)

        when: "the organization user is deleted by org admin via HTTP DELETE"
        def response = client.exchange(
                authenticated(HttpRequest.DELETE("/organization-users/${userId}/${orgId}"), orgAdminId.toString(), ["STANDARD_USER"])
        )

        then: "200 OK is returned"
        response.status == HttpStatus.OK

        and: "the record is removed from the database"
        !organizationUserRepository.findById(new OrganizationUserId(userId, orgId)).isPresent()

        cleanup:
        executeUpdate("DELETE FROM organization_users WHERE user_id IN (?, ?) AND organization_id = ?", userId, orgAdminId, orgId)
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", userId, orgAdminId)
    }

    def "DELETE | should throw 403 Forbidden when standard user attempts to delete org user"() {
        given: "an unauthorized standard user"
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        UUID targetUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "attacker-del-ou@example.com")
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", targetUserId, "target-del-ou@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Target Org Del OU")

        when: "the standard user attempts to delete org user via HTTP DELETE"
        client.exchange(
                authenticated(HttpRequest.DELETE("/organization-users/${targetUserId}/${orgId}"), userId.toString(), ["STANDARD_USER"])
        )

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        cleanup:
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", userId, targetUserId)
    }

    def "DELETE | should fail to delete a non-existent organization user when called by admin"() {
        given: "random non-existent user and organization IDs"
        def nonExistentUserId = UUID.randomUUID()
        def nonExistentOrgId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/organization-users/${nonExistentUserId}/${nonExistentOrgId}"), adminId.toString()))

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve organization users with pagination"() {
        when: "requesting organization users via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organization-users?size=5")), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
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
        def totalCount = sql.firstRow("SELECT count(*) as count FROM organization_users").count
        allOrgUsers.size() == totalCount
    }
}
