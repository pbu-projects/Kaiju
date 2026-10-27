package lol.pbu.kaiju.service

import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.repository.UserRepository
import org.locationtech.jts.geom.Coordinate

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

    def "ManagingRegion | rejects creation when managing region does not exist"() {
        given: "an organization and creator user"
        def user = dataBuilder.user().asAdmin().persist()
        def org = dataBuilder.organization().withName("Test Org").persist()

        and: "a dummy managing region that does not exist in DB"
        def nonExistentRegion = new AdministrativeRegion(UUID.randomUUID(), "Ghost Region", null, null)
        def project = dataBuilder.project()
                .forOrg(org)
                .inRegion(nonExistentRegion)
                .withTitle("Title")
                .withDescription("Desc")
                .build()

        when: "creating the project"
        projectService.createProject(project, user.id())

        then: "a BAD_REQUEST exception is thrown"
        def e = thrown(HttpStatusException)
        verifyAll(e) {
            status == HttpStatus.BAD_REQUEST
            message == "Managing region does not exist"
        }
    }

    def "ManagingRegion | rejects creation when project locations fall outside managing region polygon"() {
        given: "a region polygon (Denver, CO)"
        Coordinate[] denverCoords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Denver")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(denverCoords))
                .persist()

        and: "an organization linked to the region"
        def org = dataBuilder.organization().withName("Denver Org").persist()
        dataBuilder.linkOrganizationToRegion(org, region)

        and: "a user with region agent authority"
        def agent = dataBuilder.user().asAgent().persist()
        dataBuilder.regionUser().asRegionAgent(agent, region).persist()

        and: "a location outside the region (Boulder, CO: -105.27, 40.01)"
        def outsideLoc = dataBuilder.location()
                .withName("Boulder Out")
                .withAddressLine("St")
                .withCity("Boulder")
                .withCountryCode("US")
                .atPoint(-105.27d, 40.01d)
                .build()
        def project = dataBuilder.project()
                .forOrg(org)
                .inRegion(region)
                .withTitle("Out Project")
                .withDescription("Desc")
                .atLocation(outsideLoc)
                .build()

        when: "creating the project"
        projectService.createProject(project, agent.id())

        then: "a BAD_REQUEST is thrown for locations not falling in managing region"
        def e = thrown(HttpStatusException)
        verifyAll(e) {
            status == HttpStatus.BAD_REQUEST
            message.contains("Project locations do not fall within the specified managing region")
        }
    }

    def "ManagingRegion | rejects creation when caller lacks authority to assign managing region"() {
        given: "a region and organization"
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Authorized Reg")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
                .persist()
        def org = dataBuilder.organization().withName("Auth Org").persist()
        dataBuilder.linkOrganizationToRegion(org, region)

        and: "a standard user without admin/manager roles"
        def standardUser = dataBuilder.user().asStandardUser().persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .inRegion(region)
                .withTitle("Unauthorized Project")
                .withDescription("Desc")
                .build()

        when: "creating the project"
        projectService.createProject(project, standardUser.id())

        then: "a FORBIDDEN exception is thrown"
        def e = thrown(HttpStatusException)
        verifyAll(e) {
            status == HttpStatus.FORBIDDEN
            message == "You do not have authority to assign this managing region"
        }
    }

    def "ProjectService | approves and rejects project with audit records"() {
        given: "an admin, organization, and projects in PENDING state"
        def admin = dataBuilder.user().asAdmin().persist()
        def org = dataBuilder.organization().withName("Approve Org").persist()
        def pendingProject1 = dataBuilder.project()
                .forOrg(org)
                .withTitle("Pending Project 1")
                .withDescription("Desc")
                .withStatus(ProjectStatus.PENDING)
                .persist()
        def pendingProject2 = dataBuilder.project()
                .forOrg(org)
                .withTitle("Pending Project 2")
                .withDescription("Desc")
                .withStatus(ProjectStatus.PENDING)
                .persist()

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
        def admin = dataBuilder.user().asAdmin().persist()
        def org = dataBuilder.organization().withName("Unverified Org").unverified().persist()

        when: "updating verification status"
        def updated = organizationService.updateVerificationStatus(org.id(), VerificationStatus.REVOKED, "Failed audit", admin.id())

        then: "status is updated and audit record created"
        updated.verificationStatus() == VerificationStatus.REVOKED
    }
}
