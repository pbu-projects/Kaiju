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
import lol.pbu.kaiju.domain.AdministrativeRegionAuditLog
import lol.pbu.kaiju.model.RegionAuditAction
import lol.pbu.kaiju.repository.AdministrativeRegionAuditLogRepository

import java.time.OffsetDateTime
import java.util.UUID

@Property(name = "micronaut.security.reject-not-found", value = "false")
@MicronautTest(transactional = false)
class AdministrativeRegionAuditLogControllerSpec extends BaseControllerSpec {

    @Inject
    AdministrativeRegionAuditLogRepository auditLogRepository

    @Inject
    AdministrativeRegionAuditLogController auditLogController

    private UUID createdLogId
    private UUID testRegionId
    private UUID otherRegionId

    def setup() {
        testRegionId = UUID.randomUUID()
        otherRegionId = UUID.randomUUID()
        def user = dataBuilder.user().asAdmin().persist()

        def log1 = auditLogRepository.save(new AdministrativeRegionAuditLog(
                null,
                testRegionId,
                user,
                RegionAuditAction.CREATE,
                "{\"name\":\"Test Region\"}",
                OffsetDateTime.now()
        ))
        auditLogRepository.save(new AdministrativeRegionAuditLog(
                null,
                testRegionId,
                user,
                RegionAuditAction.UPDATE,
                "{\"previousName\":\"Test Region\",\"newName\":\"Updated Region\"}",
                OffsetDateTime.now()
        ))
        auditLogRepository.save(new AdministrativeRegionAuditLog(
                null,
                otherRegionId,
                user,
                RegionAuditAction.DELETE,
                "{\"deletedName\":\"Other Region\"}",
                OffsetDateTime.now()
        ))

        createdLogId = log1.id()
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing administrative region audit log by ID"() {
        given: "an existing administrative region audit log"
        UUID id = createdLogId

        when: "the audit log is requested by its ID via HTTP GET"
        def response = client.exchange(
                asGlobalAdmin(HttpRequest.GET("/administrative-region-audit-logs/${id}")),
                AdministrativeRegionAuditLog
        )
        AdministrativeRegionAuditLog result = response.body()

        then: "200 OK is returned with the correct audit log details"
        verifyAll {
            response.status == HttpStatus.OK
            result.id() == id
            result.regionId() == testRegionId
            result.action() == RegionAuditAction.CREATE
            result.actor() != null
            result.metadata() != null
        }
    }

    def "READ | should return 404 for a non-existent audit log ID"() {
        when: "a non-existent audit log is requested via HTTP GET"
        client.exchange(
                asGlobalAdmin(HttpRequest.GET("/administrative-region-audit-logs/${UUID.randomUUID()}")),
                AdministrativeRegionAuditLog
        )

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /administrative-region-audit-logs/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to read an audit log"
        client.exchange(HttpRequest.GET("/administrative-region-audit-logs/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve audit logs with pagination"() {
        when: "requesting audit logs via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/administrative-region-audit-logs?size=5")), Map)

        then: "the response is 200 OK with content list"
        Map body = response.body()
        verifyAll {
            response.status == HttpStatus.OK
            body.content instanceof List
        }
    }

    def "LIST | should filter audit logs by regionId query parameter"() {
        when: "requesting audit logs filtered by test region ID"
        def response = client.exchange(
                asGlobalAdmin(HttpRequest.GET("/administrative-region-audit-logs?regionId=${testRegionId}")),
                Map
        )

        then: "only logs matching the given region ID are returned"
        Map body = response.body()
        List<Map> content = (List<Map>) body.content
        verifyAll {
            response.status == HttpStatus.OK
            content.size() == 2
            content.every { it.regionId == testRegionId.toString() }
        }
    }

    def "LIST | should drain all administrative region audit logs sequentially using cursors"() {
        setup:
        Set<AdministrativeRegionAuditLog> allLogs = new LinkedHashSet<>()
        int pageSize = 2
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("id")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<AdministrativeRegionAuditLog> page = auditLogController.getAdministrativeRegionAuditLogs(null, pageable)
            allLogs.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all audit logs from the database"
        def totalCount = auditLogRepository.count()
        verifyAll {
            allLogs.size() == totalCount
            allLogs.size() >= 3
        }
    }

    /********** IMMUTABILITY Tests **********/

    def "IMMUTABILITY | should reject POST /administrative-region-audit-logs"() {
        when: "attempting to create an audit log directly over HTTP"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/administrative-region-audit-logs", [:])))

        then: "HTTP 405 or 404 is returned"
        def e = thrown(HttpClientResponseException)
        e.status in [HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND]
    }

    def "IMMUTABILITY | should reject PUT /administrative-region-audit-logs/{id}"() {
        when: "attempting to edit an audit log directly over HTTP"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/administrative-region-audit-logs/${createdLogId}", [:])))

        then: "HTTP 405 or 404 is returned"
        def e = thrown(HttpClientResponseException)
        e.status in [HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND]
    }

    def "IMMUTABILITY | should reject DELETE /administrative-region-audit-logs/{id}"() {
        when: "attempting to delete an audit log directly over HTTP"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/administrative-region-audit-logs/${createdLogId}")))

        then: "HTTP 405 or 404 is returned"
        def e = thrown(HttpClientResponseException)
        e.status in [HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND]
    }
}
