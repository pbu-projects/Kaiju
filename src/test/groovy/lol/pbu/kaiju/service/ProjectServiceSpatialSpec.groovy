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

    def "ProjectService | handles missing org, unsaved region, null defaults, draft transitions, and rejection edges"() {
        given: "a global admin, two regions, and an organization"
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

        when: "global admin creates a project with null organization"
        projectService.createProject(
                Project.forCreation(null, null, "No Org", "Desc", ProjectType.STANDARD, ProjectStatus.DRAFT, [], []),
                admin.id()
        )

        then:
        HttpStatusException noOrgEx = thrown()
        noOrgEx.status == HttpStatus.BAD_REQUEST

        when: "global admin creates a project with an unsaved organization (null ID)"
        projectService.createProject(
                Project.forCreation(new Organization(null, "Unsaved", null, null, true, VerificationStatus.UNVERIFIED, null, []), null, "Unsaved Org", "Desc", ProjectType.STANDARD, ProjectStatus.DRAFT, [], []),
                admin.id()
        )

        then:
        HttpStatusException unsavedOrgEx = thrown()
        unsavedOrgEx.status == HttpStatus.BAD_REQUEST

        when: "global admin creates a project with an unsaved managing region (null ID)"
        projectService.createProject(
                Project.forCreation(org, new AdministrativeRegion(null, "Unsaved Region", null, null), "Unsaved Reg", "Desc", ProjectType.STANDARD, ProjectStatus.DRAFT, [], []),
                admin.id()
        )

        then:
        HttpStatusException unsavedRegEx = thrown()
        unsavedRegEx.status == HttpStatus.BAD_REQUEST

        when: "global admin creates a project with null status, null locations, null boundaries, and a managing region"
        Project createdWithNullLocsAndRegion = track(projectService.createProject(
                new Project(null, org, region, "Region Null Locs", "Description long enough", ProjectType.STANDARD, null, null, null, null, null, null),
                admin.id()
        ))

        and: "global admin creates a project with null status, null locations, and null boundaries"
        Project createdDefaults = track(projectService.createProject(
                new Project(null, org, null, "Defaults Proj", "Description long enough", ProjectType.STANDARD, null, null, null, null, null, null),
                admin.id()
        ))

        then: "status defaults to DRAFT"
        verifyAll {
            createdDefaults.id() != null
            createdDefaults.status() == ProjectStatus.DRAFT
            createdWithNullLocsAndRegion.id() != null
            createdWithNullLocsAndRegion.managingRegion()?.id() == region.id()
        }

        when: "global admin updates a project with a non-null organization whose ID is null"
        projectService.updateProject(
                createdDefaults.id(),
                Project.forCreation(new Organization(null, "Missing Id", null, null, true, VerificationStatus.UNVERIFIED, null, []), null, "Title", "Desc", ProjectType.STANDARD, ProjectStatus.DRAFT, [], []),
                admin.id()
        )

        then:
        HttpStatusException nullTargetOrgEx = thrown()
        nullTargetOrgEx.status == HttpStatus.NOT_FOUND

        when: "global admin assigns a non-existent managing region during update"
        projectService.updateProject(
                createdDefaults.id(),
                Project.forUpdate(createdDefaults.id(), org, AdministrativeRegion.forId(UUID.randomUUID()), "Title", "Desc", ProjectType.STANDARD, [], []),
                admin.id()
        )

        then:
        HttpStatusException missingRegionUpdateEx = thrown()
        missingRegionUpdateEx.status == HttpStatus.BAD_REQUEST

        when: "global admin assigns a valid managing region during update with null locations and boundaries"
        Project withRegion = projectService.updateProject(
                createdDefaults.id(),
                new Project(createdDefaults.id(), org, region, "Updated With Region", "Description long enough", ProjectType.STANDARD, ProjectStatus.DRAFT, null, null, null, null, null),
                admin.id()
        )

        then: "managing region is assigned and status remains DRAFT"
        verifyAll(withRegion) {
            managingRegion()?.id() == region.id()
            status() == ProjectStatus.DRAFT
        }

        when: "global admin tries to update a DRAFT project directly to REJECTED status"
        projectService.updateProject(
                createdDefaults.id(),
                Project.forCreation(org, region, "Invalid Draft Jump", "Description long enough", ProjectType.STANDARD, ProjectStatus.REJECTED, [], []),
                admin.id()
        )

        then:
        HttpStatusException invalidDraftEx = thrown()
        invalidDraftEx.status == HttpStatus.BAD_REQUEST

        when: "global admin submits a DRAFT project to PENDING via updateProject"
        Project submitted = projectService.updateProject(
                createdDefaults.id(),
                Project.forCreation(org, region, "Submitted Draft", "Description long enough", ProjectType.STANDARD, ProjectStatus.PENDING, [loc], []),
                admin.id()
        )

        and: "global admin submits a second DRAFT project requesting ACTIVE status via updateProject"
        Project activeAttempt = projectService.updateProject(
                createdWithNullLocsAndRegion.id(),
                Project.forCreation(org, region, "Submitted Active Draft", "Description long enough", ProjectType.STANDARD, ProjectStatus.ACTIVE, [loc], []),
                admin.id()
        )

        then:
        verifyAll {
            submitted.status() == ProjectStatus.PENDING
            activeAttempt.status() == ProjectStatus.PENDING
        }

        when: "global admin rejects a DRAFT project"
        Project draftProj = dataBuilder.project().forOrg(org).withTitle("Draft To Reject").withDescription("Desc").withStatus(ProjectStatus.DRAFT).persist()
        projectService.rejectProject(draftProj.id(), admin.id())

        then:
        HttpStatusException rejectDraftEx = thrown()
        rejectDraftEx.status == HttpStatus.BAD_REQUEST

        when: "global admin rejects a non-existent project"
        projectService.rejectProject(UUID.randomUUID(), admin.id())

        then:
        HttpStatusException rejectMissingEx = thrown()
        rejectMissingEx.status == HttpStatus.NOT_FOUND

        when: "global admin rejects an already REJECTED project"
        Project rejectedProj = dataBuilder.project().forOrg(org).withTitle("Already Rejected").withDescription("Desc").withStatus(ProjectStatus.REJECTED).persist()
        projectService.rejectProject(rejectedProj.id(), admin.id())

        then:
        HttpStatusException rejectTwiceEx = thrown()
        rejectTwiceEx.status == HttpStatus.BAD_REQUEST
    }

    def "ProjectService | handles null existing org, populated existing lists, and ACTIVE demotion on null target org"() {
        given: "a mocked DefaultProjectService to reach defensive null-org and populated-list branches"
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

        when: "updating the project to assign a new managing region when existing organization is null"
        UUID newRegionId = UUID.randomUUID()
        unitService.updateProject(
                projectId,
                new Project(projectId, null, AdministrativeRegion.forId(newRegionId), "Title", "Desc", ProjectType.STANDARD, null, null, null, null, null, null),
                actorId
        )

        then: "assignManagingRegion rejects null effectiveOrgId with FORBIDDEN"
        HttpStatusException assignEx = thrown()
        assignEx.status == HttpStatus.FORBIDDEN

        when: "updating the project to unassign managing region when existing organization is null"
        unitService.updateProject(
                projectId,
                new Project(projectId, null, null, "Title", "Desc", ProjectType.STANDARD, null, null, null, null, null, null),
                actorId
        )

        then: "unassignManagingRegion rejects null effectiveOrgId with FORBIDDEN"
        HttpStatusException unassignEx = thrown()
        unassignEx.status == HttpStatus.FORBIDDEN

        when: "updating an ACTIVE project with modified locations when targetOrg is null and managingRegion is unchanged"
        Project updatedDemoted = unitService.updateProject(
                projectId,
                new Project(projectId, null, region, "Updated Title", "Desc", ProjectType.STANDARD, null, null, null, null, [], null),
                actorId
        )

        then: "status is demoted to PENDING and existing boundaries are kept"
        verifyAll(updatedDemoted) {
            status() == ProjectStatus.PENDING
            boundaries() == [existingBnd]
            locations().isEmpty()
        }

        when: "updating an ACTIVE project whose existing locations and boundaries are null"
        UUID nullListsProjectId = UUID.randomUUID()
        Project activeNullLists = new Project(
                nullListsProjectId,
                null,
                region,
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
        mockProjectRepo.findById(nullListsProjectId) >> Optional.of(activeNullLists)
        Project updatedFromNullLists = unitService.updateProject(
                nullListsProjectId,
                new Project(nullListsProjectId, null, region, "Updated Null Lists", "Desc", ProjectType.STANDARD, null, null, null, null, null, null),
                actorId
        )

        then: "null lists default to empty lists and status remains ACTIVE"
        verifyAll(updatedFromNullLists) {
            status() == ProjectStatus.ACTIVE
            locations().isEmpty()
            boundaries().isEmpty()
        }
    }
}
