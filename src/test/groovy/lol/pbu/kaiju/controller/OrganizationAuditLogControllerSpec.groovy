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
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.OrganizationAuditLogRepository

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
