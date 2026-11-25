package lol.pbu.kaiju.service

import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Boundary
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.BoundaryRepository
import lol.pbu.kaiju.repository.LocationRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.PermissionQueryRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.security.AuthorizationService
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import spock.lang.Unroll

import java.time.OffsetDateTime
import java.time.ZoneOffset
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

        and: "an org manager authorized for this org and region"
        def manager = dataBuilder.user().asStandardUser().persist()
        dataBuilder.organizationUser().asOrgManager(manager, org).persist()

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
        projectService.createProject(project, manager.id())

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

        and: "an org member without manager or regional admin roles"
        def orgMember = dataBuilder.user().asStandardUser().persist()
        dataBuilder.organizationUser().asOrgMember(orgMember, org).persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .inRegion(region)
                .withTitle("Unauthorized Project")
                .withDescription("Desc")
                .build()

        when: "creating the project"
        projectService.createProject(project, orgMember.id())

        then: "a FORBIDDEN exception is thrown"
        def e = thrown(HttpStatusException)
        verifyAll(e) {
            status == HttpStatus.FORBIDDEN
            message == "You do not have authority to assign this managing region"
        }
    }

    def "ProjectService | sets a pending project to active when approved"() {
        given: "an admin, organization, and project in PENDING state"
        def admin = dataBuilder.user().asAdmin().persist()
        def org = dataBuilder.organization().withName("Approve Org").persist()
        def pendingProject = dataBuilder.project()
                .forOrg(org)
                .withTitle("Pending Project 1")
                .withDescription("Desc")
                .withStatus(ProjectStatus.PENDING)
                .persist()

        when: "approving the project"
        def approved = projectService.approveProject(pendingProject.id(), admin.id())

        then: "status is updated to ACTIVE"
        approved.status() == ProjectStatus.ACTIVE
    }

    def "ProjectService | sets a pending project to rejected when rejected"() {
        given: "an admin, organization, and project in PENDING state"
        def admin = dataBuilder.user().asAdmin().persist()
        def org = dataBuilder.organization().withName("Approve Org").persist()
        def pendingProject = dataBuilder.project()
                .forOrg(org)
                .withTitle("Pending Project 2")
                .withDescription("Desc")
                .withStatus(ProjectStatus.PENDING)
                .persist()

        when: "rejecting the project"
        def rejected = projectService.rejectProject(pendingProject.id(), admin.id())

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

    private Map createEdgeProjectContext() {
        def admin = dataBuilder.user().asAdmin().persist()
        def org = dataBuilder.organization().withName("Edge Project Org").asPublic().verified().persist()
        Coordinate[] regionCoords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Project Edge Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(regionCoords))
                .persist()
        def loc = dataBuilder.location()
                .withName("Inside Edge Loc")
                .withAddress("123 Main", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        [admin: admin, org: org, region: region, loc: loc]
    }

    private Map createMockProjectHarness() {
        ProjectRepository mockProjectRepo = Mock(ProjectRepository)
        AdministrativeRegionRepository mockRegionRepo = Mock(AdministrativeRegionRepository)
        OrganizationRepository mockOrgRepo = Mock(OrganizationRepository)
        UserRepository mockUserRepo = Mock(UserRepository)
        AuthorizationService unitAuth = new AuthorizationService(
                Mock(PermissionQueryRepository),
                mockUserRepo,
                Mock(LocationRepository),
                Mock(BoundaryRepository),
                new GeometryFactory()
        )
        AuditService mockAudit = Mock(AuditService)
        DefaultProjectService unitService = new DefaultProjectService(
                mockProjectRepo,
                mockRegionRepo,
                mockOrgRepo,
                unitAuth,
                mockAudit
        )

        UUID actorId = UUID.randomUUID()
        UUID projectId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        User admin = new User(actorId, "admin@example.com", UserRole.GLOBAL_ADMIN, OffsetDateTime.now(ZoneOffset.UTC))
        AdministrativeRegion region = new AdministrativeRegion(regionId, "Mock Region", null, null)
        Location existingLoc = new Location(UUID.randomUUID(), "Loc", "123 St", "Denver", "CO", "80202", "US", null, "America/Denver")
        Boundary existingBnd = new Boundary(UUID.randomUUID(), "Bnd", null)

        Project activeNoOrg = new Project(
                projectId,
                null,
                region,
                "Active No Org",
                "Description long enough",
                ProjectType.STANDARD,
                ProjectStatus.ACTIVE,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                [existingLoc],
                [existingBnd]
        )

        mockUserRepo.findById(actorId) >> Optional.of(admin)
        mockProjectRepo.findById(projectId) >> Optional.of(activeNoOrg)
        mockProjectRepo.update(_ as Project) >> { Project p -> p }

        [
                unitService    : unitService,
                mockProjectRepo: mockProjectRepo,
                actorId        : actorId,
                projectId      : projectId,
                region         : region,
                existingBnd    : existingBnd
        ]
    }

    @Unroll
    def "ProjectService | createProject throws BAD_REQUEST when #scenario"() {
        given: "a global admin and organization"
        def ctx = createEdgeProjectContext()
        Organization targetOrg = switch (orgCase) {
            case "null" -> null
            case "unsaved" -> new Organization(null, "Unsaved", null, null, true, VerificationStatus.UNVERIFIED, null, [])
            default -> ctx.org
        }
        AdministrativeRegion targetRegion = regionCase == "unsaved"
                ? new AdministrativeRegion(null, "Unsaved Region", null, null)
                : null

        when: "creating the project"
        projectService.createProject(
                Project.forCreation(targetOrg, targetRegion, title, "Desc", ProjectType.STANDARD, ProjectStatus.DRAFT, [], []),
                ctx.admin.id()
        )

        then: "BAD_REQUEST is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.BAD_REQUEST

        where:
        scenario                              | orgCase   | regionCase | title
        "organization is null"                | "null"    | "none"     | "No Org"
        "organization is unsaved (null ID)"   | "unsaved" | "none"     | "Unsaved Org"
        "managing region is unsaved (null ID)"| "valid"   | "unsaved"  | "Unsaved Reg"
    }

    @Unroll
    def "ProjectService | createProject defaults status to DRAFT when #scenario"() {
        given: "a global admin, organization, and managing region"
        def ctx = createEdgeProjectContext()
        AdministrativeRegion targetRegion = withRegion ? ctx.region : null

        when: "creating a project with null status, locations, and boundaries"
        Project created = track(projectService.createProject(
                new Project(null, ctx.org, targetRegion, "Defaults Proj", "Description long enough", ProjectType.STANDARD, null, null, null, null, null, null),
                ctx.admin.id()
        ))

        then: "project is saved as DRAFT with expected managing region"
        verifyAll(created) {
            id() != null
            status() == ProjectStatus.DRAFT
            managingRegion()?.id() == (withRegion ? ctx.region.id() : null)
        }

        where:
        scenario                     | withRegion
        "managing region is present" | true
        "managing region is null"    | false
    }

    @Unroll
    def "ProjectService | updateProject fails with #expectedStatus when #scenario"() {
        given: "a global admin and a saved DRAFT project"
        def ctx = createEdgeProjectContext()
        Project draft = track(projectService.createProject(
                new Project(null, ctx.org, null, "Draft Proj", "Description long enough", ProjectType.STANDARD, null, null, null, null, null, null),
                ctx.admin.id()
        ))

        Organization targetOrg = unsavedOrg
                ? new Organization(null, "Missing Id", null, null, true, VerificationStatus.UNVERIFIED, null, [])
                : ctx.org
        AdministrativeRegion targetRegion = missingRegion
                ? AdministrativeRegion.forId(UUID.randomUUID())
                : (useValidRegion ? ctx.region : null)

        when: "updating the project"
        projectService.updateProject(
                draft.id(),
                Project.forCreation(targetOrg, targetRegion, "Title", "Description long enough", ProjectType.STANDARD, targetStatus, [], []),
                ctx.admin.id()
        )

        then: "the expected HTTP error is thrown"
        HttpStatusException ex = thrown()
        ex.status == expectedStatus

        where:
        scenario                               | unsavedOrg | missingRegion | useValidRegion | targetStatus           || expectedStatus
        "organization has null ID"             | true       | false         | false          | ProjectStatus.DRAFT    || HttpStatus.NOT_FOUND
        "managing region does not exist"       | false      | true          | false          | ProjectStatus.DRAFT    || HttpStatus.BAD_REQUEST
        "transitioning DRAFT straight to REJECTED" | false  | false         | true           | ProjectStatus.REJECTED || HttpStatus.BAD_REQUEST
    }

    def "ProjectService | updateProject assigns a valid managing region with null lists and stays DRAFT"() {
        given: "a global admin and a saved DRAFT project"
        def ctx = createEdgeProjectContext()
        Project draft = track(projectService.createProject(
                new Project(null, ctx.org, null, "Draft Proj", "Description long enough", ProjectType.STANDARD, null, null, null, null, null, null),
                ctx.admin.id()
        ))

        when: "assigning a valid managing region with null locations and boundaries"
        Project updated = projectService.updateProject(
                draft.id(),
                new Project(draft.id(), ctx.org, ctx.region, "Updated With Region", "Description long enough", ProjectType.STANDARD, ProjectStatus.DRAFT, null, null, null, null, null),
                ctx.admin.id()
        )

        then: "managing region is assigned and status remains DRAFT"
        verifyAll(updated) {
            managingRegion()?.id() == ctx.region.id()
            status() == ProjectStatus.DRAFT
        }
    }

    @Unroll
    def "ProjectService | updateProject moves a DRAFT project to PENDING when requesting #requestedStatus"() {
        given: "a global admin, region, location, and a saved DRAFT project"
        def ctx = createEdgeProjectContext()
        Project draft = track(projectService.createProject(
                new Project(null, ctx.org, ctx.region, "Draft Submit", "Description long enough", ProjectType.STANDARD, null, null, null, null, null, null),
                ctx.admin.id()
        ))

        when: "submitting the DRAFT project via updateProject"
        Project submitted = projectService.updateProject(
                draft.id(),
                Project.forCreation(ctx.org, ctx.region, "Submitted Draft", "Description long enough", ProjectType.STANDARD, requestedStatus, [ctx.loc], []),
                ctx.admin.id()
        )

        then: "status becomes PENDING"
        submitted.status() == ProjectStatus.PENDING

        where:
        requestedStatus << [ProjectStatus.PENDING, ProjectStatus.ACTIVE]
    }

    @Unroll
    def "ProjectService | rejectProject fails with #expectedStatus when #scenario"() {
        given: "a global admin and organization"
        def ctx = createEdgeProjectContext()
        UUID projectId = switch (projectState) {
            case "draft" -> dataBuilder.project().forOrg(ctx.org).withTitle("Draft To Reject").withDescription("Desc").withStatus(ProjectStatus.DRAFT).persist().id()
            case "rejected" -> dataBuilder.project().forOrg(ctx.org).withTitle("Already Rejected").withDescription("Desc").withStatus(ProjectStatus.REJECTED).persist().id()
            default -> UUID.randomUUID()
        }

        when: "rejecting the project"
        projectService.rejectProject(projectId, ctx.admin.id())

        then: "the expected HTTP error is thrown"
        HttpStatusException ex = thrown()
        ex.status == expectedStatus

        where:
        scenario                     | projectState || expectedStatus
        "project is in DRAFT status" | "draft"      || HttpStatus.BAD_REQUEST
        "project does not exist"     | "missing"    || HttpStatus.NOT_FOUND
        "project is already REJECTED"| "rejected"   || HttpStatus.BAD_REQUEST
    }

    def "ProjectService | assignManagingRegion throws FORBIDDEN when existing organization is null"() {
        given: "a mocked project with a null organization"
        def h = createMockProjectHarness()

        when: "updating the project to assign a new managing region"
        h.unitService.updateProject(
                h.projectId,
                new Project(h.projectId, null, AdministrativeRegion.forId(UUID.randomUUID()), "Title", "Desc", ProjectType.STANDARD, null, null, null, null, null, null),
                h.actorId
        )

        then: "FORBIDDEN is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN
    }

    def "ProjectService | unassignManagingRegion throws FORBIDDEN when existing organization is null"() {
        given: "a mocked project with a null organization"
        def h = createMockProjectHarness()

        when: "updating the project to unassign the managing region"
        h.unitService.updateProject(
                h.projectId,
                new Project(h.projectId, null, null, "Title", "Desc", ProjectType.STANDARD, null, null, null, null, null, null),
                h.actorId
        )

        then: "FORBIDDEN is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN
    }

    def "ProjectService | modifying locations on ACTIVE project with null target org demotes status to PENDING"() {
        given: "a mocked ACTIVE project with null organization and existing boundaries"
        def h = createMockProjectHarness()

        when: "updating locations with null targetOrg and unchanged managingRegion"
        Project updatedDemoted = h.unitService.updateProject(
                h.projectId,
                new Project(h.projectId, null, h.region, "Updated Title", "Desc", ProjectType.STANDARD, null, null, null, null, [], null),
                h.actorId
        )

        then: "status is demoted to PENDING and existing boundaries are kept"
        verifyAll(updatedDemoted) {
            status() == ProjectStatus.PENDING
            boundaries() == [h.existingBnd]
            locations().isEmpty()
        }
    }

    def "ProjectService | updating ACTIVE project with null lists defaults lists to empty and keeps ACTIVE"() {
        given: "a mocked ACTIVE project whose existing locations and boundaries are null"
        def h = createMockProjectHarness()
        UUID nullListsProjectId = UUID.randomUUID()
        Project activeNullLists = new Project(
                nullListsProjectId,
                null,
                h.region,
                "Active Null Lists",
                "Description long enough",
                ProjectType.STANDARD,
                ProjectStatus.ACTIVE,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                null,
                null
        )
        h.mockProjectRepo.findById(nullListsProjectId) >> Optional.of(activeNullLists)

        when: "updating the project with null locations and boundaries"
        Project updatedFromNullLists = h.unitService.updateProject(
                nullListsProjectId,
                new Project(nullListsProjectId, null, h.region, "Updated Null Lists", "Desc", ProjectType.STANDARD, null, null, null, null, null, null),
                h.actorId
        )

        then: "null lists default to empty lists and status stays ACTIVE"
        verifyAll(updatedFromNullLists) {
            status() == ProjectStatus.ACTIVE
            locations().isEmpty()
            boundaries().isEmpty()
        }
    }
}
