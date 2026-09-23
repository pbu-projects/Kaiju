package lol.pbu.kaiju.security

import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository
import spock.lang.Shared

import java.time.OffsetDateTime

@MicronautTest(transactional = true)
class ProjectSecurityServiceSpec extends BaseControllerSpec {

    @Inject
    ProjectSecurityService service

    @Inject
    UserRepository userRepository

    @Shared
    UUID orgId = UUID.randomUUID()

    @Shared
    UUID projectId = UUID.randomUUID()

    def setupSpec() {
        // Seed a real organization and project using raw SQL to avoid validation constraint issues
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Test Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Security Test Project', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projectId, orgId)
    }

    User saveUser(UserRole role) {
        userRepository.save(new User(null, "sec-test-${UUID.randomUUID()}@example.com", role, OffsetDateTime.now()))
    }

    def "canModifyProject returns true for GLOBAL_ADMIN without any org lookup"() {
        given:
        User admin = saveUser(UserRole.GLOBAL_ADMIN)

        expect:
        // A global admin on any project (even one with no org match) can modify it
        service.canModifyProject(admin.id(), new lol.pbu.kaiju.domain.Project(
                projectId, null, null, "Title", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING,
                OffsetDateTime.now(), null, null, [], []
        )) == true
    }

    def "canModifyProject returns false when user is not org manager"() {
        given:
        User user = saveUser(UserRole.STANDARD_USER)
        UUID anotherOrg = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Other Org', true)", anotherOrg)

        and: "a project belonging to anotherOrg"
        UUID anotherProject = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Other Project', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, anotherProject, anotherOrg)

        and: "user has NO org_user entry for that org"
        def org = new lol.pbu.kaiju.domain.Organization(anotherOrg, "Other Org", null, null, true,
                lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED, null, [])
        def project = new lol.pbu.kaiju.domain.Project(anotherProject, org, null, "Other Project", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING,
                OffsetDateTime.now(), null, null, [], [])

        expect:
        service.canModifyProject(user.id(), project) == false
    }

    def "canModifyProject returns false when project has no organization"() {
        given:
        User user = saveUser(UserRole.STANDARD_USER)
        def project = new lol.pbu.kaiju.domain.Project(
                UUID.randomUUID(), null, null, "Title", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING,
                OffsetDateTime.now(), null, null, [], []
        )

        expect:
        service.canModifyProject(user.id(), project) == false
    }

    def "canModifyProject throws NOT_FOUND when user does not exist"() {
        given:
        def project = new lol.pbu.kaiju.domain.Project(
                projectId, null, null, "Title", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING,
                OffsetDateTime.now(), null, null, [], []
        )

        when:
        service.canModifyProject(UUID.randomUUID(), project)

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.NOT_FOUND
    }

    def "authorizeRegionalAdminApproval passes for GLOBAL_ADMIN without any DB jurisdiction check"() {
        given:
        User admin = saveUser(UserRole.GLOBAL_ADMIN)

        when:
        service.authorizeRegionalAdminApproval(admin.id(), projectId)

        then:
        noExceptionThrown()
    }

    def "authorizeRegionalAdminApproval throws FORBIDDEN when agent has no region covering the project"() {
        given: "a REGION_AGENT with no region assignment at all"
        User agent = saveUser(UserRole.REGION_AGENT)

        when: "they try to approve a project"
        service.authorizeRegionalAdminApproval(agent.id(), projectId)

        then: "they are denied because they have no jurisdiction"
        HttpStatusException e = thrown()
        e.status == HttpStatus.FORBIDDEN
    }

    def "authorizeRegionalAdminApproval throws NOT_FOUND for unknown user"() {
        when:
        service.authorizeRegionalAdminApproval(UUID.randomUUID(), projectId)

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.NOT_FOUND
    }

    def "canAssignToOrganization returns true for GLOBAL_ADMIN without any org lookup"() {
        given:
        User admin = saveUser(UserRole.GLOBAL_ADMIN)

        expect:
        service.canAssignToOrganization(admin.id(), UUID.randomUUID()) == true
    }

    def "canAssignToOrganization returns true when user is org manager of target org"() {
        given:
        User user = saveUser(UserRole.STANDARD_USER)
        UUID targetOrg = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Target Org', true)", targetOrg)
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", user.id(), targetOrg)

        expect:
        service.canAssignToOrganization(user.id(), targetOrg) == true
        service.canAssignToOrganization(user.id(), new lol.pbu.kaiju.domain.Organization(targetOrg, "Target Org", null, null, true, lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED, null, [])) == true
    }

    def "canAssignToOrganization returns false when user is not org manager of target org"() {
        given:
        User user = saveUser(UserRole.STANDARD_USER)
        UUID targetOrg = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Target Org', true)", targetOrg)

        expect:
        service.canAssignToOrganization(user.id(), targetOrg) == false
    }

    def "canAssignToOrganization returns false when organizationId or organization is null"() {
        given:
        User user = saveUser(UserRole.STANDARD_USER)

        expect:
        service.canAssignToOrganization(user.id(), (UUID) null) == false
        service.canAssignToOrganization(user.id(), (lol.pbu.kaiju.domain.Organization) null) == false
        service.canAssignToOrganization(user.id(), new lol.pbu.kaiju.domain.Organization(null, "No ID Org", null, null, true, lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED, null, [])) == false
    }

    def "canAssignToOrganization throws NOT_FOUND when user does not exist"() {
        when:
        service.canAssignToOrganization(UUID.randomUUID(), UUID.randomUUID())

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.NOT_FOUND
    }
}
