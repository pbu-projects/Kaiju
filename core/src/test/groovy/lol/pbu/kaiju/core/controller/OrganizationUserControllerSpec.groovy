package lol.pbu.kaiju.core.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import lol.pbu.kaiju.core.domain.OrganizationUser
import lol.pbu.kaiju.core.domain.OrganizationUserId
import lol.pbu.kaiju.core.model.OrganizationUserRole
import lol.pbu.kaiju.core.repository.OrganizationUserRepository
import spock.lang.Unroll

class OrganizationUserControllerSpec extends BaseControllerSpec {

    @Inject
    OrganizationUserRepository organizationUserRepository

    @Inject
    OrganizationUserController organizationUserController

    private UUID getExistingUserId() {
        sql.firstRow("SELECT id FROM users LIMIT 1").id as UUID
    }

    private UUID getExistingOrganizationId() {
        sql.firstRow("SELECT id FROM organizations LIMIT 1").id as UUID
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid organization user"() {
        given: "a new organization user connection"
        // Generate a new user and organization to avoid primary key collisions
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "orguser@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Test Org for User")

        def orgUserId = new OrganizationUserId(userId, orgId)
        def orgUser = new OrganizationUser(orgUserId, OrganizationUserRole.ORG_ADMIN)

        when: "the organization user is added"
        OrganizationUser saved = organizationUserController.addOrganizationUser(orgUser)

        then: "it can be retrieved"
        saved.id().userId() == userId
        saved.id().organizationId() == orgId
        saved.role() == OrganizationUserRole.ORG_ADMIN

        cleanup:
        executeUpdate("DELETE FROM organization_users WHERE user_id = ? AND organization_id = ?", userId, orgId)
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    @Unroll
    def "CREATE | should fail to save organization user with invalid data: #testCase"(String testCase, OrganizationUser orgUser) {
        when: "an attempt is made to add with invalid data"
        organizationUserController.addOrganizationUser(orgUser)

        then: "an exception is thrown"
        thrown(Exception)

        where:
        testCase    | orgUser
        "Null Role" | new OrganizationUser(new OrganizationUserId(UUID.randomUUID(), UUID.randomUUID()), null)
        "Null ID"   | new OrganizationUser(null, OrganizationUserRole.ORG_ADMIN)
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing organization user by composite ID"() {
        given: "an existing user and organization"
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "orguser-read@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Test Org for User Read")
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_ADMIN')", userId, orgId)

        when: "the organization user is requested"
        def result = organizationUserController.getOrganizationUser(userId, orgId)

        then: "the correct record is returned"
        result.isPresent()
        result.get().id().userId() == userId
        result.get().id().organizationId() == orgId
        result.get().role() == OrganizationUserRole.ORG_ADMIN

        cleanup:
        executeUpdate("DELETE FROM organization_users WHERE user_id = ? AND organization_id = ?", userId, orgId)
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    def "READ | should return empty for a non-existent composite ID"() {
        when: "a non-existent organization user is requested"
        def result = organizationUserController.getOrganizationUser(UUID.randomUUID(), UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing organization user role"() {
        given: "an existing organization user"
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "orguser-update@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Test Org for User Update")
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MEMBER')", userId, orgId)

        def updateRequest = new OrganizationUser(null, OrganizationUserRole.ORG_ADMIN)

        when: "the organization user is updated"
        OrganizationUser updated = organizationUserController.updateOrganizationUser(userId, orgId, updateRequest)

        then: "the changes are reflected"
        updated.role() == OrganizationUserRole.ORG_ADMIN

        and: "persisted in the database"
        def role = sql.firstRow("SELECT role FROM organization_users WHERE user_id = ? AND organization_id = ?", [userId, orgId]).role
        role == 'ORG_ADMIN'

        cleanup:
        executeUpdate("DELETE FROM organization_users WHERE user_id = ? AND organization_id = ?", userId, orgId)
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    def "UPDATE | should fail to update a non-existent organization user"() {
        given: "a non-existent composite ID and update request"
        def updateRequest = new OrganizationUser(null, OrganizationUserRole.ORG_ADMIN)

        when: "an update is attempted"
        organizationUserController.updateOrganizationUser(UUID.randomUUID(), UUID.randomUUID(), updateRequest)

        then: "an exception is thrown"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should handle update of non-existent gracefully when using no-look"() {
        given: "a non-existent composite ID and update request"
        def updateRequest = new OrganizationUser(new OrganizationUserId(UUID.randomUUID(), UUID.randomUUID()), OrganizationUserRole.ORG_ADMIN)

        when: "a no-look update is attempted"
        organizationUserController.updateOrganizationUserNoLook(UUID.randomUUID(), UUID.randomUUID(), updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing organization user"() {
        given: "an existing organization user"
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "orguser-delete@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Test Org for User Delete")
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MEMBER')", userId, orgId)

        when: "the organization user is deleted"
        organizationUserController.deleteOrganizationUser(userId, orgId)

        then: "it no longer exists"
        !organizationUserRepository.existsById(new OrganizationUserId(userId, orgId))

        cleanup:
        executeUpdate("DELETE FROM organization_users WHERE user_id = ? AND organization_id = ?", userId, orgId)
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    /********** LIST Tests **********/

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
