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
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.OrganizationAuditLog
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.dto.CreateOrganizationAuditLogCommand
import lol.pbu.kaiju.dto.UpdateOrganizationAuditLogCommand
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.OrganizationAuditLogRepository
import spock.lang.Unroll

import java.time.OffsetDateTime
import java.util.UUID

import static lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class OrganizationAuditLogControllerSpec extends BaseControllerSpec {

    @Inject
    OrganizationAuditLogRepository organizationAuditLogRepository

    @Inject
    OrganizationAuditLogController organizationAuditLogController

    private Organization getRandomOrganization() {
        def orgRow = sql.firstRow("SELECT id, name FROM organizations LIMIT 1")
        if (!orgRow) {
            throw new IllegalStateException("No organizations found in database to link audit log to.")
        }
        new Organization(orgRow.id as UUID, orgRow.name as String, null, null, true, UNVERIFIED, null, [])
    }

    private User getRandomUser() {
        def userRow = sql.firstRow("SELECT id, email, role FROM users LIMIT 1")
        if (!userRow) {
            throw new IllegalStateException("No users found in database to link audit log to.")
        }
        new User(userRow.id as UUID, userRow.email as String, UserRole.valueOf(userRow.role as String), OffsetDateTime.now())
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid organization audit log"() {
        given: "a new valid organization audit log command"
        def org = getRandomOrganization()
        def actor = getRandomUser()
        def command = new CreateOrganizationAuditLogCommand(
                org.id(),
                actor.id(),
                "UNVERIFIED",
                "VERIFIED",
                "Verified by system admin"
        )

        when: "the organization audit log is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/organization-audit-logs", command)), OrganizationAuditLog)
        OrganizationAuditLog saved = response.body()

        then: "200 OK is returned and record is persisted"
        response.status == HttpStatus.OK
        saved.id() != null
        saved.organization().id() == org.id()
        saved.actor().id() == actor.id()
        saved.previousStatus() == "UNVERIFIED"
        saved.newStatus() == "VERIFIED"
        saved.reason() == "Verified by system admin"

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM organization_audit_logs WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.organization().id() == organization_id
            saved.actor().id() == actor_id
            saved.previousStatus() == previous_status
            saved.newStatus() == new_status
            saved.reason() == reason
        }

        cleanup:
        if (saved?.id() != null) {
            executeUpdate("DELETE FROM organization_audit_logs WHERE id = ?", saved.id())
        }
    }

    @Unroll
    def "CREATE | should fail to save organization audit log with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add an organization audit log with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/organization-audit-logs", payload)), OrganizationAuditLog)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase                | payload
        "Null Organization ID"  | [organizationId: null, actorId: UUID.randomUUID(), previousStatus: "UNVERIFIED", newStatus: "VERIFIED", reason: "Reason"]
        "Null Actor ID"         | [organizationId: UUID.randomUUID(), actorId: null, previousStatus: "UNVERIFIED", newStatus: "VERIFIED", reason: "Reason"]
        "Null Previous Status"  | [organizationId: UUID.randomUUID(), actorId: UUID.randomUUID(), previousStatus: null, newStatus: "VERIFIED", reason: "Reason"]
        "Blank Previous Status" | [organizationId: UUID.randomUUID(), actorId: UUID.randomUUID(), previousStatus: "   ", newStatus: "VERIFIED", reason: "Reason"]
        "Null New Status"       | [organizationId: UUID.randomUUID(), actorId: UUID.randomUUID(), previousStatus: "UNVERIFIED", newStatus: null, reason: "Reason"]
        "Blank New Status"      | [organizationId: UUID.randomUUID(), actorId: UUID.randomUUID(), previousStatus: "UNVERIFIED", newStatus: "   ", reason: "Reason"]
    }

    def "CREATE | should reject unauthenticated POST /organization-audit-logs with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to create an audit log"
        client.exchange(HttpRequest.POST("/organization-audit-logs", new CreateOrganizationAuditLogCommand(UUID.randomUUID(), UUID.randomUUID(), "UNVERIFIED", "VERIFIED", "Reason")))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing organization audit log by ID"() {
        given: "seed an organization audit log"
        def org = getRandomOrganization()
        def actor = getRandomUser()
        def log = organizationAuditLogRepository.save(new OrganizationAuditLog(null, org, actor, "UNVERIFIED", "VERIFIED", "Reason", OffsetDateTime.now()))
        UUID id = log.id()

        when: "the organization audit log is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organization-audit-logs/${id}")), OrganizationAuditLog)
        OrganizationAuditLog result = response.body()

        then: "200 OK is returned with the correct organization audit log"
        response.status == HttpStatus.OK
        result.id() == id

        cleanup:
        executeUpdate("DELETE FROM organization_audit_logs WHERE id = ?", id)
    }

    def "READ | should return 404 for a non-existent organization audit log ID"() {
        when: "a non-existent organization audit log is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/organization-audit-logs/${UUID.randomUUID()}")), OrganizationAuditLog)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /organization-audit-logs/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to read an audit log"
        client.exchange(HttpRequest.GET("/organization-audit-logs/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing organization audit log"() {
        given: "an existing organization audit log"
        def org = getRandomOrganization()
        def actor = getRandomUser()
        def log = organizationAuditLogRepository.save(new OrganizationAuditLog(null, org, actor, "UNVERIFIED", "VERIFIED", "Reason", OffsetDateTime.now()))
        UUID id = log.id()

        def command = new UpdateOrganizationAuditLogCommand("VERIFIED", "REVOKED", "Revoked credentials")

        when: "the organization audit log is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/organization-audit-logs/${id}", command)), OrganizationAuditLog)
        OrganizationAuditLog updated = response.body()

        then: "200 OK is returned with updated data"
        response.status == HttpStatus.OK
        updated.id() == id
        updated.previousStatus() == "VERIFIED"
        updated.newStatus() == "REVOKED"
        updated.reason() == "Revoked credentials"

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT previous_status, new_status, reason FROM organization_audit_logs WHERE id = ?", [id])
        verifyAll(dbResult) {
            previous_status == 'VERIFIED'
            new_status == 'REVOKED'
            reason == 'Revoked credentials'
        }

        cleanup:
        executeUpdate("DELETE FROM organization_audit_logs WHERE id = ?", id)
    }

    def "UPDATE | should fail to update a non-existent organization audit log"() {
        given: "a random non-existent ID and an update command"
        def nonExistentId = UUID.randomUUID()
        def command = new UpdateOrganizationAuditLogCommand("UNVERIFIED", "VERIFIED", "Reason")

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/organization-audit-logs/${nonExistentId}", command)), OrganizationAuditLog)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing organization audit log"() {
        given: "an organization audit log to be deleted"
        def org = getRandomOrganization()
        def actor = getRandomUser()
        def log = organizationAuditLogRepository.save(new OrganizationAuditLog(null, org, actor, "UNVERIFIED", "VERIFIED", "Reason", OffsetDateTime.now()))
        UUID id = log.id()
        assert organizationAuditLogRepository.existsById(id)

        when: "the organization audit log is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/organization-audit-logs/${id}")))

        then: "200 OK is returned"
        response.status == HttpStatus.OK

        and: "the organization audit log no longer exists in repository or database"
        !organizationAuditLogRepository.findById(id).isPresent()
        sql.firstRow("SELECT count(*) as count FROM organization_audit_logs WHERE id = ?", [id]).count == 0
    }

    def "DELETE | should fail to delete a non-existent organization audit log"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/organization-audit-logs/${nonExistentId}")))

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve organization audit logs with pagination"() {
        when: "requesting organization audit logs via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organization-audit-logs?size=5")), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
    }

    def "LIST | should fully drain all organization audit logs sequentially using cursors"() {
        setup:
        Set<OrganizationAuditLog> allLogs = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("id")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<OrganizationAuditLog> page = organizationAuditLogController.getOrganizationAuditLogs(pageable)
            allLogs.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected size matches the DB count"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM organization_audit_logs").count
        allLogs.size() == totalCount
    }
}
