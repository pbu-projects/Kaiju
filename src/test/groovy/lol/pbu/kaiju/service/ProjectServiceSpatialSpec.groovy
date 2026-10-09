package lol.pbu.kaiju.service

import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.repository.UserRepository
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

import java.time.OffsetDateTime
import java.util.UUID

@MicronautTest(transactional = false)
class ProjectServiceSpatialSpec extends BaseControllerSpec {

    @Inject
    ProjectService projectService

    @Inject
    OrganizationService organizationService

    @Inject
    ProjectRepository projectRepository

    @Inject
    AdministrativeRegionRepository regionRepository

    @Inject
    OrganizationRepository organizationRepository

    @Inject
    UserRepository userRepository

    def cleanup() {
        executeUpdate("DELETE FROM shifts")
        executeUpdate("DELETE FROM project_audit_logs")
        executeUpdate("DELETE FROM project_locations")
        executeUpdate("DELETE FROM projects")
        executeUpdate("DELETE FROM organization_regions")
        executeUpdate("DELETE FROM region_users")
        executeUpdate("DELETE FROM organization_users")
        executeUpdate("DELETE FROM organizations")
        executeUpdate("DELETE FROM locations")
        executeUpdate("DELETE FROM administrative_regions")
        executeUpdate("DELETE FROM users WHERE email LIKE '%@example.com'")
    }

    private User saveUser(UserRole role) {
        def user = new User(null, "user-${UUID.randomUUID()}@example.com", role, OffsetDateTime.now())
        return userRepository.save(user)
    }

    private Organization saveOrganization(String name) {
        def org = new Organization(null, name, "https://example.com", null, true, VerificationStatus.VERIFIED, null, [])
        return organizationRepository.save(org)
    }

    def "ManagingRegion | rejects creation when managing region does not exist"() {
        given: "an organization and creator user"
        def user = saveUser(UserRole.GLOBAL_ADMIN)
        def org = saveOrganization("Test Org")

        and: "a dummy managing region that does not exist in DB"
        def nonExistentRegion = new AdministrativeRegion(UUID.randomUUID(), "Ghost Region", null, null)
        def project = new Project(null, org, nonExistentRegion, "Title", "Desc", ProjectType.STANDARD, null, null, null, null, [], [])

        when: "creating the project"
        projectService.createProject(project, user.id())

        then: "a BAD_REQUEST exception is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.BAD_REQUEST
        e.message == "Managing region does not exist"
    }

    def "ManagingRegion | rejects creation when project locations fall outside managing region polygon"() {
        given: "a region polygon (Denver, CO)"
        def regId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Denver', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        def region = regionRepository.findById(regId).get()

        and: "an organization linked to the region"
        def org = saveOrganization("Denver Org")
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org.id(), regId)

        and: "a user with region agent authority"
        def agent = saveUser(UserRole.REGION_AGENT)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_AGENT')", agent.id(), regId)

        and: "a location outside the region (Boulder, CO: -105.27, 40.01)"
        def gf = new GeometryFactory(new PrecisionModel(), 4326)
        def outsideLoc = new Location(UUID.randomUUID(), "Boulder Out", "St", "Boulder", null, null, "US", gf.createPoint(new Coordinate(-105.27, 40.01)))
        def project = new Project(null, org, region, "Out Project", "Desc", ProjectType.STANDARD, null, null, null, null, [outsideLoc], [])

        when: "creating the project"
        projectService.createProject(project, agent.id())

        then: "a BAD_REQUEST is thrown for locations not falling in managing region"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.BAD_REQUEST
        e.message.contains("Project locations do not fall within the specified managing region")
    }

    def "ManagingRegion | rejects creation when caller lacks authority to assign managing region"() {
        given: "a region and organization"
        def regId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Authorized Reg', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        def region = regionRepository.findById(regId).get()
        def org = saveOrganization("Auth Org")
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org.id(), regId)

        and: "a standard user without admin/manager roles"
        def standardUser = saveUser(UserRole.STANDARD_USER)
        def project = new Project(null, org, region, "Unauthorized Project", "Desc", ProjectType.STANDARD, null, null, null, null, [], [])

        when: "creating the project"
        projectService.createProject(project, standardUser.id())

        then: "a FORBIDDEN exception is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.FORBIDDEN
        e.message == "You do not have authority to assign this managing region"
    }

    def "ProjectService | approves and rejects project with audit records"() {
        given: "an admin, organization, and projects in PENDING state"
        def admin = saveUser(UserRole.GLOBAL_ADMIN)
        def org = saveOrganization("Approve Org")
        def pendingProject1 = projectRepository.save(new Project(
                null, org, null, "Pending Project 1", "Desc", ProjectType.STANDARD,
                ProjectStatus.PENDING, OffsetDateTime.now(), null, null, [], []
        ))
        def pendingProject2 = projectRepository.save(new Project(
                null, org, null, "Pending Project 2", "Desc", ProjectType.STANDARD,
                ProjectStatus.PENDING, OffsetDateTime.now(), null, null, [], []
        ))

        when: "approving the project"
        def approved = projectService.approveProject(pendingProject1.id(), admin.id())

        then: "status is updated to ACTIVE"
        approved.status() == ProjectStatus.ACTIVE

        when: "rejecting the project"
        def rejected = projectService.rejectProject(pendingProject2.id(), admin.id())

        then: "status is updated to REJECTED"
        rejected.status() == ProjectStatus.REJECTED
    }

    def "OrganizationService | updates verification status and generates audit log"() {
        given: "an admin and unverified organization"
        def admin = saveUser(UserRole.GLOBAL_ADMIN)
        def org = saveOrganization("Unverified Org")

        when: "updating verification status"
        def updated = organizationService.updateVerificationStatus(org.id(), VerificationStatus.REVOKED, "Failed audit", admin.id())

        then: "status is updated and audit record created"
        updated.verificationStatus() == VerificationStatus.REVOKED
    }
}
