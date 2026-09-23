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

    def "canReassignProject returns true for GLOBAL_ADMIN without any jurisdiction check"() {
        given:
        User admin = saveUser(UserRole.GLOBAL_ADMIN)
        def project = new lol.pbu.kaiju.domain.Project(
                projectId, null, null, "Title", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING,
                OffsetDateTime.now(), null, null, [], []
        )

        expect:
        service.canReassignProject(admin.id(), project) == true
    }

    def "canReassignProject returns false for STANDARD_USER even if org manager"() {
        given: "a standard user who is an org manager"
        User user = saveUser(UserRole.STANDARD_USER)
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", user.id(), orgId)
        def project = new lol.pbu.kaiju.domain.Project(
                projectId, null, null, "Title", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING,
                OffsetDateTime.now(), null, null, [], []
        )

        expect:
        service.canReassignProject(user.id(), project) == false
    }

    def "canReassignProject returns false for REGION_AGENT without jurisdiction"() {
        given: "a regional agent with no jurisdiction over the project"
        User agent = saveUser(UserRole.REGION_AGENT)
        def project = new lol.pbu.kaiju.domain.Project(
                projectId, null, null, "Title", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING,
                OffsetDateTime.now(), null, null, [], []
        )

        expect:
        service.canReassignProject(agent.id(), project) == false
    }

    def "canReassignProject throws NOT_FOUND when user does not exist"() {
        given:
        def project = new lol.pbu.kaiju.domain.Project(
                projectId, null, null, "Title", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING,
                OffsetDateTime.now(), null, null, [], []
        )

        when:
        service.canReassignProject(UUID.randomUUID(), project)

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.NOT_FOUND
    }

    def "areAllLocationsInOrgRegion returns false when locations is null or empty"() {
        given:
        def projectNoLocs = new lol.pbu.kaiju.domain.Project(
                projectId, null, null, "Title", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING,
                OffsetDateTime.now(), null, null, [], []
        )
        def projectNullLocs = new lol.pbu.kaiju.domain.Project(
                projectId, null, null, "Title", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING,
                OffsetDateTime.now(), null, null, null, []
        )

        expect:
        service.areAllLocationsInOrgRegion(projectNoLocs, orgId) == false
        service.areAllLocationsInOrgRegion(projectNullLocs, orgId) == false
    }

    def "areAllLocationsInOrgRegion evaluates geographic boundaries correctly"() {
        given: "a region and an organization mapped to it"
        def testRegionId = UUID.randomUUID()
        def testOrgId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom) 
            VALUES (?, 'Denver Area', ST_GeogFromText('POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))'))
        """, testRegionId)
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Geo Org', true)", testOrgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", testOrgId, testRegionId)

        and: "locations inside and outside the region"
        def geomFactory = new org.locationtech.jts.geom.GeometryFactory(new org.locationtech.jts.geom.PrecisionModel(), 4326)
        def insidePoint = geomFactory.createPoint(new org.locationtech.jts.geom.Coordinate(-104.9903, 39.7392))
        def outsidePoint = geomFactory.createPoint(new org.locationtech.jts.geom.Coordinate(-105.2705, 40.0150))
        def insideLoc = new lol.pbu.kaiju.domain.Location(UUID.randomUUID(), "Inside", "123 St", "Denver", "CO", "80202", "US", insidePoint)
        def outsideLoc = new lol.pbu.kaiju.domain.Location(UUID.randomUUID(), "Outside", "456 St", "Boulder", "CO", "80302", "US", outsidePoint)

        def projectInside = new lol.pbu.kaiju.domain.Project(
                UUID.randomUUID(), null, null, "Inside", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.ACTIVE,
                OffsetDateTime.now(), null, null, [insideLoc], []
        )
        def projectOutside = new lol.pbu.kaiju.domain.Project(
                UUID.randomUUID(), null, null, "Outside", "Desc",
                lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.ACTIVE,
                OffsetDateTime.now(), null, null, [outsideLoc], []
        )

        expect:
        service.areAllLocationsInOrgRegion(projectInside, testOrgId) == true
        service.areAllLocationsInOrgRegion(projectOutside, testOrgId) == false
    }
}
