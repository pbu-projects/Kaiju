package lol.pbu.kaiju.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.OrganizationUser
import lol.pbu.kaiju.domain.OrganizationUserId
import lol.pbu.kaiju.model.OrganizationUserRole
import lol.pbu.kaiju.repository.OrganizationUserRepository
import spock.lang.Unroll

import java.security.Principal

class OrganizationUserControllerSpec extends BaseControllerSpec {

    @Inject
    OrganizationUserRepository organizationUserRepository

    @Inject
    OrganizationUserController organizationUserController

    Principal createPrincipal(UUID userId) {
        new Principal() {
            @Override
            String getName() {
                return userId.toString()
            }
        }
    }

    private UUID getExistingUserId() {
        sql.firstRow("SELECT id FROM users LIMIT 1").id as UUID
    }

    private UUID getExistingOrganizationId() {
        sql.firstRow("SELECT id FROM organizations LIMIT 1").id as UUID
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid organization user when called by org admin"() {
        given: "a new organization user connection and an org admin"
        // Generate a new user and organization to avoid primary key collisions
        UUID orgAdminId = UUID.randomUUID()
        UUID userId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", orgAdminId, "orgadmin@example.com")
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "orguser@example.com")
        executeUpdate("INSERT INTO organizations (id, name, website_url) VALUES (?, ?, 'http://test.org')", orgId, "Test Org for User")
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_ADMIN')", orgAdminId, orgId)

        def orgUserId = new OrganizationUserId(userId, orgId)
        def orgUser = new OrganizationUser(orgUserId, OrganizationUserRole.ORG_ADMIN)

        when: "the organization user is added by org admin"
        OrganizationUser saved = organizationUserController.addOrganizationUser(orgUser, createPrincipal(orgAdminId))

        then: "it can be retrieved"
        saved.id().userId() == userId
        saved.id().organizationId() == orgId
        saved.role() == OrganizationUserRole.ORG_ADMIN

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

        def orgUser = new OrganizationUser(new OrganizationUserId(targetUserId, orgId), OrganizationUserRole.ORG_ADMIN)

        when: "the standard user attempts to add org user"
        organizationUserController.addOrganizationUser(orgUser, createPrincipal(userId))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status.code == 403

        cleanup:
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", userId, targetUserId)
    }

    @Unroll
    def "CREATE | should fail to save organization user with invalid data: #testCase"(String testCase, OrganizationUser orgUser) {
        given: "a global admin caller"
        UUID adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-org-val@example.com")

        when: "an attempt is made to add with invalid data"
        organizationUserController.addOrganizationUser(orgUser, createPrincipal(adminId))

        then: "an exception is thrown"
        thrown(Exception)

        cleanup:
        executeUpdate("DELETE FROM users WHERE id = ?", adminId)

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

        def updateRequest = new OrganizationUser(null, OrganizationUserRole.ORG_ADMIN)

        when: "the organization user is updated by org admin"
        OrganizationUser updated = organizationUserController.updateOrganizationUser(userId, orgId, updateRequest, createPrincipal(orgAdminId))

        then: "the changes are reflected"
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

        def updateRequest = new OrganizationUser(null, OrganizationUserRole.ORG_ADMIN)

        when: "the standard user attempts to update org user"
        organizationUserController.updateOrganizationUser(targetUserId, orgId, updateRequest, createPrincipal(userId))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status.code == 403

        cleanup:
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", userId, targetUserId)
    }

    def "UPDATE | should fail to update a non-existent organization user when called by admin"() {
        given: "a global admin, non-existent composite ID and update request"
        UUID adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-upd-ne@example.com")
        def updateRequest = new OrganizationUser(null, OrganizationUserRole.ORG_ADMIN)

        when: "an update is attempted by admin"
        organizationUserController.updateOrganizationUser(UUID.randomUUID(), UUID.randomUUID(), updateRequest, createPrincipal(adminId))

        then: "an exception is thrown"
        def e = thrown(HttpStatusException)
        e.status.code == 404

        cleanup:
        executeUpdate("DELETE FROM users WHERE id = ?", adminId)
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

        when: "the organization user is deleted by org admin"
        organizationUserController.deleteOrganizationUser(userId, orgId, createPrincipal(orgAdminId))

        then: "it no longer exists"
        !organizationUserRepository.existsById(new OrganizationUserId(userId, orgId))

        cleanup:
        executeUpdate("DELETE FROM organization_users WHERE user_id = ? AND organization_id = ?", orgAdminId, orgId)
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

        when: "the standard user attempts to delete org user"
        organizationUserController.deleteOrganizationUser(targetUserId, orgId, createPrincipal(userId))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status.code == 403

        cleanup:
        executeUpdate("DELETE FROM organizations WHERE id = ?", orgId)
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", userId, targetUserId)
    }

    def "DELETE | should fail to delete a non-existent organization user when called by admin"() {
        given: "a global admin and random non-existent user and organization ID"
        UUID adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-del-ne@example.com")
        def nonExistentUserId = UUID.randomUUID()
        def nonExistentOrgId = UUID.randomUUID()

        when: "a delete is attempted by admin"
        organizationUserController.deleteOrganizationUser(nonExistentUserId, nonExistentOrgId, createPrincipal(adminId))

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404

        cleanup:
        executeUpdate("DELETE FROM users WHERE id = ?", adminId)
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
