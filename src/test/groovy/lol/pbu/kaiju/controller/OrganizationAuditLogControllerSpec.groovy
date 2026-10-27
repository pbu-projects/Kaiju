package lol.pbu.kaiju.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.OrganizationAuditLog
import lol.pbu.kaiju.repository.OrganizationAuditLogRepository

import java.time.OffsetDateTime
import java.util.UUID

@MicronautTest(transactional = false)
class OrganizationAuditLogControllerSpec extends BaseControllerSpec {

    @Inject
    OrganizationAuditLogRepository organizationAuditLogRepository

    @Inject
    OrganizationAuditLogController organizationAuditLogController

    /********** READ Tests **********/

    def "READ | should retrieve an existing organization audit log by ID"() {
        given: "seed an organization audit log"
        def org = dataBuilder.organization().persist()
        def actor = dataBuilder.user().persist()
        def log = organizationAuditLogRepository.save(new OrganizationAuditLog(null, org, actor, "UNVERIFIED", "VERIFIED", "Reason", OffsetDateTime.now()))
        UUID id = log.id()

        when: "the organization audit log is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organization-audit-logs/${id}")), OrganizationAuditLog)
        OrganizationAuditLog result = response.body()

        then: "200 OK is returned with the correct organization audit log"
        verifyAll {
            response.status == HttpStatus.OK
            result.id() == id
        }

        cleanup:
        organizationAuditLogRepository.removeById(id)
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
        Map body = response.body()
        verifyAll {
            response.status == HttpStatus.OK
            body.content instanceof List
        }
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
        def totalCount = organizationAuditLogRepository.count()
        allLogs.size() == totalCount
    }
}
