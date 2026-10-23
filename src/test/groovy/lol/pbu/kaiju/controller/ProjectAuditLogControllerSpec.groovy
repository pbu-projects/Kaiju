package lol.pbu.kaiju.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.ProjectAuditLog
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.repository.ProjectAuditLogRepository

import java.util.UUID

@MicronautTest(transactional = false)
class ProjectAuditLogControllerSpec extends BaseControllerSpec {

    @Inject
    ProjectAuditLogRepository projectAuditLogRepository

    @Inject
    ProjectAuditLogController projectAuditLogController

    def setup() {
        UUID actorId = TestPersonas.SARAH_ADMIN.id

        def projRow = sql.firstRow("SELECT id FROM projects LIMIT 1")
        UUID projectId
        if (projRow) {
            projectId = projRow.id as UUID
        } else {
            def orgId = UUID.randomUUID()
            executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Audit Test Org', true, 'VERIFIED')", orgId)
            projectId = UUID.randomUUID()
            executeUpdate("INSERT INTO projects (id, organization_id, title, description, project_type, status) VALUES (?, ?, 'Audit Test Proj', 'Test project description exceeding 20 chars', 'STANDARD', 'DRAFT')", projectId, orgId)
        }

        sql.execute("INSERT INTO project_audit_logs (project_id, actor_id, action) VALUES (?, ?, 'CREATED')", [projectId, actorId])
        sql.execute("INSERT INTO project_audit_logs (project_id, actor_id, action) VALUES (?, ?, 'EDITED')", [projectId, actorId])
    }

    def cleanup() {
        sql.execute("DELETE FROM project_audit_logs WHERE action IN ('CREATED', 'EDITED')")
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing project audit log by ID"() {
        given: "an existing project audit log"
        def logRow = sql.firstRow("SELECT id FROM project_audit_logs LIMIT 1")
        UUID id = logRow.id as UUID

        when: "the project audit log is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/project-audit-logs/${id}")), ProjectAuditLog)
        ProjectAuditLog result = response.body()

        then: "200 OK is returned with the correct project audit log"
        verifyAll {
            response.status == HttpStatus.OK
            result.id() == id
        }
    }

    def "READ | should return 404 for a non-existent project audit log ID"() {
        when: "a non-existent project audit log is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/project-audit-logs/${UUID.randomUUID()}")), ProjectAuditLog)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /project-audit-logs/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to read an audit log"
        client.exchange(HttpRequest.GET("/project-audit-logs/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve project audit logs with pagination"() {
        when: "requesting project audit logs via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/project-audit-logs?size=5")), Map)

        then: "the response is 200 OK with content list"
        Map body = response.body()
        verifyAll {
            response.status == HttpStatus.OK
            body.content instanceof List
        }
    }

    def "LIST | should fully drain all project audit logs sequentially using cursors"() {
        setup:
        Set<ProjectAuditLog> allLogs = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("id")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<ProjectAuditLog> page = projectAuditLogController.getProjectAuditLogs(pageable)
            allLogs.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all project audit logs from the database"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM project_audit_logs").count
        verifyAll {
            allLogs.size() == totalCount
            allLogs.size() >= 2
        }
    }
}
