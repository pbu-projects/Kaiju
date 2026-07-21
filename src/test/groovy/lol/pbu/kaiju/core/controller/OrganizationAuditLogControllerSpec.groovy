package lol.pbu.kaiju.core.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.core.domain.Organization
import lol.pbu.kaiju.core.domain.OrganizationAuditLog
import lol.pbu.kaiju.core.domain.User
import lol.pbu.kaiju.core.model.UserRole
import lol.pbu.kaiju.core.model.VerificationStatus
import lol.pbu.kaiju.core.repository.OrganizationAuditLogRepository
import spock.lang.Unroll

import java.time.OffsetDateTime

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
        new Organization(orgRow.id as UUID, orgRow.name as String, null, null, true, VerificationStatus.UNVERIFIED, null, [])
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
        given: "a new valid organization audit log"
        def org = getRandomOrganization()
        def actor = getRandomUser()
        def newLog = new OrganizationAuditLog(
                null,
                org,
                actor,
                "UNVERIFIED",
                "VERIFIED",
                "Verified by system admin",
                OffsetDateTime.now()
        )

        when: "the organization audit log is added"
        OrganizationAuditLog saved = organizationAuditLogController.addOrganizationAuditLog(newLog)

        then: "the organization audit log is persisted with a generated ID"
        verifyAll {
            saved.id() != null
            saved.organization().id() == org.id()
            saved.actor().id() == actor.id()
            saved.previousStatus() == "UNVERIFIED"
            saved.newStatus() == "VERIFIED"
            saved.reason() == "Verified by system admin"
        }

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
    }

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "CREATE | should fail to save organization audit log with invalid data: #testCase"(String testCase, Closure<OrganizationAuditLog> logCreator) {
        when: "an attempt is made to add an organization audit log with invalid data"
        organizationAuditLogController.addOrganizationAuditLog(logCreator())

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, logCreator] << {
            def validOrg = { -> getRandomOrganization() }
            def validActor = { -> getRandomUser() }

            def invalidCases = [
                    [field: 'organization', value: { -> null }, caseName: "Null Organization"],
                    [field: 'actor', value: { -> null }, caseName: "Null Actor"],
                    [field: 'previousStatus', value: { -> null }, caseName: "Null Previous Status"],
                    [field: 'previousStatus', value: { -> " " }, caseName: "Blank Previous Status"],
                    [field: 'newStatus', value: { -> null }, caseName: "Null New Status"],
                    [field: 'newStatus', value: { -> " " }, caseName: "Blank New Status"]
            ]

            return invalidCases.collect { invalidCase ->
                [
                        invalidCase.caseName,
                        { ->
                            new OrganizationAuditLog(
                                    null,
                                    (invalidCase.field == 'organization' ? invalidCase.value() : validOrg()) as Organization,
                                    (invalidCase.field == 'actor' ? invalidCase.value() : validActor()) as User,
                                    (invalidCase.field == 'previousStatus' ? invalidCase.value() : "UNVERIFIED") as String,
                                    (invalidCase.field == 'newStatus' ? invalidCase.value() : "VERIFIED") as String,
                                    "Reason",
                                    OffsetDateTime.now()
                            )
                        }
                ]
            }
        }()
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing organization audit log by ID"() {
        given: "seed an organization audit log"
        def org = getRandomOrganization()
        def actor = getRandomUser()
        def log = organizationAuditLogRepository.save(new OrganizationAuditLog(null, org, actor, "UNVERIFIED", "VERIFIED", "Reason", OffsetDateTime.now()))
        UUID id = log.id()

        when: "the organization audit log is requested by its ID"
        def result = organizationAuditLogController.getOrganizationAuditLog(id)

        then: "the correct organization audit log is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
        }
    }

    def "READ | should return empty for a non-existent organization audit log ID"() {
        when: "a non-existent organization audit log is requested"
        def result = organizationAuditLogController.getOrganizationAuditLog(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing organization audit log"() {
        given: "an existing organization audit log"
        def org = getRandomOrganization()
        def actor = getRandomUser()
        def log = organizationAuditLogRepository.save(new OrganizationAuditLog(null, org, actor, "UNVERIFIED", "VERIFIED", "Reason", OffsetDateTime.now()))
        UUID id = log.id()

        def updateRequest = new OrganizationAuditLog(null, org, actor, "VERIFIED", "REVOKED", "Revoked credentials", OffsetDateTime.now())

        when: "the organization audit log is updated"
        OrganizationAuditLog updated = organizationAuditLogController.updateOrganizationAuditLog(id, updateRequest)

        then: "the returned organization audit log contains the updated data"
        verifyAll {
            updated.id() == id
            updated.previousStatus() == "VERIFIED"
            updated.newStatus() == "REVOKED"
            updated.reason() == "Revoked credentials"
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT previous_status, new_status, reason FROM organization_audit_logs WHERE id = ?", [id])
        verifyAll(dbResult) {
            previous_status == 'VERIFIED'
            new_status == 'REVOKED'
            reason == 'Revoked credentials'
        }
    }

    def "UPDATE | should fail to update a non-existent organization audit log"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def org = getRandomOrganization()
        def actor = getRandomUser()
        def updateRequest = new OrganizationAuditLog(null, org, actor, "UNVERIFIED", "VERIFIED", "Reason", OffsetDateTime.now())

        when: "an update is attempted"
        organizationAuditLogController.updateOrganizationAuditLog(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should handle update of non-existent organization audit log gracefully when using no-look"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def org = getRandomOrganization()
        def actor = getRandomUser()
        def updateRequest = new OrganizationAuditLog(null, org, actor, "UNVERIFIED", "VERIFIED", "Reason", OffsetDateTime.now())

        when: "a no-look update is attempted"
        organizationAuditLogController.updateOrganizationAuditLogNoLook(nonExistentId, updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing organization audit log"() {
        given: "a new organization audit log to be deleted"
        def org = getRandomOrganization()
        def actor = getRandomUser()
        def tempLog = new OrganizationAuditLog(null, org, actor, "UNVERIFIED", "VERIFIED", "Reason", OffsetDateTime.now())
        def saved = organizationAuditLogController.addOrganizationAuditLog(tempLog)
        UUID id = saved.id()
        assert organizationAuditLogRepository.existsById(id)

        when: "the organization audit log is deleted"
        organizationAuditLogController.deleteOrganizationAuditLog(id)

        then: "the organization audit log no longer exists in the repository or database"
        verifyAll {
            !organizationAuditLogRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM organization_audit_logs WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent organization audit log"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted"
        organizationAuditLogController.deleteOrganizationAuditLog(nonExistentId)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "DELETE | should handle deletion of non-existent organization audit log gracefully when using no-look"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a no-look delete is attempted"
        organizationAuditLogController.deleteOrganizationAuditLogNoLook(nonExistentId)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** LIST Tests **********/

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

        then: "the collected set contains all organization audit logs from the database"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM organization_audit_logs").count
        allLogs.size() == totalCount
    }
}
