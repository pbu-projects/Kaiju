package lol.pbu.kaiju.core.controller


import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.core.domain.Organization
import lol.pbu.kaiju.core.domain.Project
import lol.pbu.kaiju.core.domain.ProjectAuditLog
import lol.pbu.kaiju.core.domain.User
import lol.pbu.kaiju.core.model.AuditAction
import lol.pbu.kaiju.core.model.UserRole
import lol.pbu.kaiju.core.repository.ProjectAuditLogRepository
import spock.lang.Unroll

import java.time.OffsetDateTime

import static lol.pbu.kaiju.core.model.AuditAction.CREATED
import static lol.pbu.kaiju.core.model.ProjectStatus.DRAFT
import static lol.pbu.kaiju.core.model.ProjectType.STANDARD

class ProjectAuditLogControllerSpec extends BaseControllerSpec {

    @Inject
    ProjectAuditLogRepository projectAuditLogRepository

    @Inject
    ProjectAuditLogController projectAuditLogController

    def setupSpec() {
        // Use standaloneConnection to query and seed data
        def stmt = standaloneConnection.createStatement()
        def rsProj = stmt.executeQuery("SELECT id FROM projects LIMIT 1")
        rsProj.next()
        def projectId = UUID.fromString(rsProj.getString("id"))

        def rsUser = stmt.executeQuery("SELECT id FROM users LIMIT 1")
        rsUser.next()
        def actorId = UUID.fromString(rsUser.getString("id"))

        def ps = standaloneConnection.prepareStatement("INSERT INTO project_audit_logs (project_id, actor_id, action) VALUES (?, ?, 'CREATED')")
        ps.setObject(1, projectId)
        ps.setObject(2, actorId)
        ps.executeUpdate()

        ps = standaloneConnection.prepareStatement("INSERT INTO project_audit_logs (project_id, actor_id, action) VALUES (?, ?, 'EDITED')")
        ps.setObject(1, projectId)
        ps.setObject(2, actorId)
        ps.executeUpdate()
    }

    def cleanupSpec() {
        standaloneConnection.createStatement().execute("DELETE FROM project_audit_logs WHERE action IN ('CREATED', 'EDITED')")
    }

    private Project getRandomProject() {
        def projectRow = sql.firstRow("SELECT id, title FROM projects LIMIT 1")
        if (!projectRow) {
            throw new IllegalStateException("No projects found in database to link audit log to.")
        }
        def org = new Organization(UUID.randomUUID(), "Dummy Org", null, null, [])
        new Project(projectRow.id as UUID, org, projectRow.title as String, "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])
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
        given: "a new valid project audit log"
        def project = getRandomProject()
        def actor = getRandomUser()
        def newLog = new ProjectAuditLog(null, project, actor, CREATED, OffsetDateTime.now())

        when: "the project audit log is added"
        ProjectAuditLog saved = projectAuditLogController.addProjectAuditLog(newLog)

        then: "the project audit log is persisted with a generated ID"
        verifyAll {
            saved.id() != null
            saved.project().id() == project.id()
            saved.actor().id() == actor.id()
            saved.action() == CREATED
        }

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
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "CREATE | should fail to save project audit log with invalid data: #testCase"(String testCase, Closure<ProjectAuditLog> logCreator) {
        when: "an attempt is made to add a project audit log with invalid data"
        projectAuditLogController.addProjectAuditLog(logCreator())

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, logCreator] << {
            def validProject = { -> getRandomProject() }
            def validActor = { -> getRandomUser() }

            def invalidCases = [
                    [field: 'project', value: { -> null }, caseName: "Null Project"],
                    [field: 'actor', value: { -> null }, caseName: "Null Actor"],
                    [field: 'action', value: { -> null }, caseName: "Null Action"]
            ]

            return invalidCases.collect { invalidCase ->
                [
                        invalidCase.caseName,
                        { ->
                            new ProjectAuditLog(
                                    null,
                                    (invalidCase.field == 'project' ? invalidCase.value() : validProject()) as Project,
                                    (invalidCase.field == 'actor' ? invalidCase.value() : validActor()) as User,
                                    (invalidCase.field == 'action' ? invalidCase.value() : CREATED) as AuditAction,
                                    OffsetDateTime.now()
                            )
                        }
                ]
            }
        }()
    }

    /********** READ Tests **********/

    @Unroll
    def "READ | should retrieve an existing project audit log by ID"() {
        given: "an existing project audit log ID from the database"
        def firstRow = sql.firstRow("SELECT id FROM project_audit_logs LIMIT 1")
        assert firstRow != null
        UUID id = firstRow.id as UUID

        when: "the project audit log is requested by its ID"
        def result = projectAuditLogController.getProjectAuditLog(id)

        then: "the correct project audit log is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
        }
    }

    def "READ | should return empty for a non-existent project audit log ID"() {
        when: "a non-existent project audit log is requested"
        def result = projectAuditLogController.getProjectAuditLog(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing project audit log"() {
        given: "an existing project audit log's details"
        def logRow = sql.firstRow("SELECT id FROM project_audit_logs LIMIT 1")
        assert logRow != null
        UUID id = logRow.id as UUID
        def project = getRandomProject()
        def actor = getRandomUser()
        def updateRequest = new ProjectAuditLog(null, project, actor, AuditAction.EDITED, OffsetDateTime.now())

        when: "the project audit log is updated"
        ProjectAuditLog updated = projectAuditLogController.updateProjectAuditLog(id, updateRequest)

        then: "the returned project audit log contains the updated data"
        verifyAll {
            updated.id() == id
            updated.action() == AuditAction.EDITED
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT action FROM project_audit_logs WHERE id = ?", [id])
        verifyAll(dbResult) {
            action == 'EDITED'
        }
    }

    def "UPDATE | should fail to update a non-existent project audit log"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def project = getRandomProject()
        def actor = getRandomUser()
        def updateRequest = new ProjectAuditLog(null, project, actor, AuditAction.APPROVED, OffsetDateTime.now())

        when: "an update is attempted"
        projectAuditLogController.updateProjectAuditLog(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should handle update of non-existent project audit log gracefully when using no-look"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def project = getRandomProject()
        def actor = getRandomUser()
        def updateRequest = new ProjectAuditLog(null, project, actor, AuditAction.APPROVED, OffsetDateTime.now())

        when: "a no-look update is attempted"
        projectAuditLogController.updateProjectAuditLogNoLook(nonExistentId, updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing project audit log"() {
        given: "a new project audit log to be deleted"
        def project = getRandomProject()
        def actor = getRandomUser()
        def tempLog = new ProjectAuditLog(
                null,
                project,
                actor,
                CREATED,
                OffsetDateTime.now()
        )
        def saved = projectAuditLogController.addProjectAuditLog(tempLog)
        UUID id = saved.id()
        assert projectAuditLogRepository.existsById(id)

        when: "the project audit log is deleted"
        projectAuditLogController.deleteProjectAuditLog(id)

        then: "the project audit log no longer exists in the repository or database"
        verifyAll {
            !projectAuditLogRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM project_audit_logs WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent project audit log"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted"
        projectAuditLogController.deleteProjectAuditLog(nonExistentId)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "DELETE | should handle deletion of non-existent project audit log gracefully when using no-look"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a no-look delete is attempted"
        projectAuditLogController.deleteProjectAuditLogNoLook(nonExistentId)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** LIST Tests **********/

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
