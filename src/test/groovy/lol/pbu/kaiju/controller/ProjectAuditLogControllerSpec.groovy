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
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.ProjectAuditLog
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.dto.CreateProjectAuditLogCommand
import lol.pbu.kaiju.dto.UpdateProjectAuditLogCommand
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.ProjectAuditLogRepository
import spock.lang.Unroll

import java.time.OffsetDateTime
import java.util.UUID

import static lol.pbu.kaiju.model.AuditAction.CREATED
import static lol.pbu.kaiju.model.AuditAction.EDITED
import static lol.pbu.kaiju.model.ProjectStatus.DRAFT
import static lol.pbu.kaiju.model.ProjectType.STANDARD
import static lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class ProjectAuditLogControllerSpec extends BaseControllerSpec {

    @Inject
    ProjectAuditLogRepository projectAuditLogRepository

    @Inject
    ProjectAuditLogController projectAuditLogController

    def setup() {
        def projRow = sql.firstRow("SELECT id FROM projects LIMIT 1")
        def projectId = projRow.id as UUID

        def userRow = sql.firstRow("SELECT id FROM users LIMIT 1")
        def actorId = userRow.id as UUID

        sql.execute("INSERT INTO project_audit_logs (project_id, actor_id, action) VALUES (?, ?, 'CREATED')", [projectId, actorId])
        sql.execute("INSERT INTO project_audit_logs (project_id, actor_id, action) VALUES (?, ?, 'EDITED')", [projectId, actorId])
    }

    def cleanup() {
        sql.execute("DELETE FROM project_audit_logs WHERE action IN ('CREATED', 'EDITED')")
    }

    private Project getRandomProject() {
        def projectRow = sql.firstRow("SELECT id, title FROM projects LIMIT 1")
        if (!projectRow) {
            throw new IllegalStateException("No projects found in database to link audit log to.")
        }
        def org = new Organization(UUID.randomUUID(), "Dummy Org", null, null, true, UNVERIFIED, null, [])
        new Project(projectRow.id as UUID, org, null, projectRow.title as String, "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])
    }

    private User getRandomUser() {
        def userRow = sql.firstRow("SELECT id, email, role FROM users LIMIT 1")
        if (!userRow) {
            throw new IllegalStateException("No users found in database to link audit log to.")
        }
        new User(userRow.id as UUID, userRow.email as String, UserRole.valueOf(userRow.role as String), OffsetDateTime.now())
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid project audit log"() {
        given: "a new valid project audit log command"
        def project = getRandomProject()
        def actor = getRandomUser()
        def command = new CreateProjectAuditLogCommand(project.id(), actor.id(), CREATED)

        when: "the project audit log is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/project-audit-logs", command)), ProjectAuditLog)
        ProjectAuditLog saved = response.body()

        then: "200 OK is returned and record is persisted"
        response.status == HttpStatus.OK
        saved.id() != null
        saved.project().id() == project.id()
        saved.actor().id() == actor.id()
        saved.action() == CREATED

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM project_audit_logs WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.project().id() == project_id
            saved.actor().id() == actor_id
            saved.action().name() == action
        }
    }

    @Unroll
    def "CREATE | should fail to save project audit log with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add a project audit log with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/project-audit-logs", payload)), ProjectAuditLog)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase            | payload
        "Null Project ID"   | [projectId: null, actorId: UUID.randomUUID(), action: "CREATED"]
        "Null Actor ID"     | [projectId: UUID.randomUUID(), actorId: null, action: "CREATED"]
        "Null Action"       | [projectId: UUID.randomUUID(), actorId: UUID.randomUUID(), action: null]
    }

    def "CREATE | should reject unauthenticated POST /project-audit-logs with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to add a project audit log"
        client.exchange(HttpRequest.POST("/project-audit-logs", new CreateProjectAuditLogCommand(UUID.randomUUID(), UUID.randomUUID(), CREATED)))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
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
        response.status == HttpStatus.OK
        result.id() == id
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

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing project audit log"() {
        given: "an existing project audit log"
        def logRow = sql.firstRow("SELECT id FROM project_audit_logs WHERE action = 'CREATED' LIMIT 1")
        UUID id = logRow.id as UUID
        def command = new UpdateProjectAuditLogCommand(EDITED)

        when: "the project audit log is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/project-audit-logs/${id}", command)), ProjectAuditLog)
        ProjectAuditLog updated = response.body()

        then: "200 OK is returned and changes are reflected"
        response.status == HttpStatus.OK
        updated.id() == id
        updated.action() == EDITED

        and: "persisted in the database"
        def dbResult = sql.firstRow("SELECT action FROM project_audit_logs WHERE id = ?", [id])
        dbResult.action == 'EDITED'
    }

    def "UPDATE | should fail to update a non-existent project audit log"() {
        given: "a random non-existent ID and command"
        def nonExistentId = UUID.randomUUID()
        def command = new UpdateProjectAuditLogCommand(EDITED)

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/project-audit-logs/${nonExistentId}", command)), ProjectAuditLog)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing project audit log"() {
        given: "a project audit log to be deleted"
        def logRow = sql.firstRow("SELECT id FROM project_audit_logs WHERE action = 'CREATED' LIMIT 1")
        UUID id = logRow.id as UUID

        when: "the project audit log is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/project-audit-logs/${id}")))

        then: "200 OK is returned"
        response.status == HttpStatus.OK

        and: "the project audit log no longer exists in repository or database"
        !projectAuditLogRepository.findById(id).isPresent()
        sql.firstRow("SELECT count(*) as count FROM project_audit_logs WHERE id = ?", [id]).count == 0
    }

    def "DELETE | should fail to delete a non-existent project audit log"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/project-audit-logs/${nonExistentId}")))

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve project audit logs with pagination"() {
        when: "requesting project audit logs via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/project-audit-logs?size=5")), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
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
