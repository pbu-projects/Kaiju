package lol.pbu.kaiju.security

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.dto.Coordinates
import lol.pbu.kaiju.dto.CreateShiftCommand
import lol.pbu.kaiju.dto.UpdateBoundaryCommand
import lol.pbu.kaiju.dto.UpdateLocationCommand
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.service.OrganizationService
import lol.pbu.kaiju.service.ProjectService
import org.locationtech.jts.geom.Coordinate

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset

@MicronautTest(transactional = false)
class UserJourneyProtectionSpec extends BaseControllerSpec {

    @Inject
    ProjectService projectService

    @Inject
    OrganizationService organizationService

    @Inject
    AuthorizationService authorizationService

    def "org manager can view own draft project while outsider gets 403 and deleted project returns 404"() {
        given: "a draft project and a soft-deleted active project for Denver Metro Branch"
        def draft = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Denver Draft ${UUID.randomUUID()}")
                .withDescription("Draft project visible only to organization members and admins.")
                .withStatus(ProjectStatus.DRAFT)
                .persist()

        def deleted = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Denver Deleted ${UUID.randomUUID()}")
                .withDescription("Deleted project that should not be returned to anyone.")
                .withStatus(ProjectStatus.ACTIVE)
                .deleted()
                .persist()

        when: "the branch manager fetches their own draft project"
        def managerRes = client.exchange(
                asOrgManager(HttpRequest.GET("/projects/${draft.id()}").accept(MediaType.APPLICATION_JSON_TYPE), TestPersonas.LAURA_LOCAL_MANAGER),
                Project
        )

        then: "they receive 200 OK with the draft project"
        verifyAll(managerRes) {
            status == HttpStatus.OK
            body().id() == draft.id()
            body().status() == ProjectStatus.DRAFT
        }

        when: "an unrelated standard user fetches the draft project"
        client.exchange(
                asStandardUser(HttpRequest.GET("/projects/${draft.id()}").accept(MediaType.APPLICATION_JSON_TYPE), TestPersonas.VICTOR_VOLUNTEER),
                Project
        )

        then: "access is denied with 403 FORBIDDEN"
        def outsiderErr = thrown(HttpClientResponseException)
        outsiderErr.status == HttpStatus.FORBIDDEN

        when: "the branch manager fetches the soft-deleted project"
        client.exchange(
                asOrgManager(HttpRequest.GET("/projects/${deleted.id()}").accept(MediaType.APPLICATION_JSON_TYPE), TestPersonas.LAURA_LOCAL_MANAGER),
                Project
        )

        then: "404 NOT_FOUND is returned"
        def deletedErr = thrown(HttpClientResponseException)
        deletedErr.status == HttpStatus.NOT_FOUND
    }

    def "global admin sees draft projects in project list while anonymous and standard users do not"() {
        given: "a non-deleted draft project with a unique title"
        def uniqueTitle = "Admin Only Draft ${UUID.randomUUID()}"
        def draft = dataBuilder.project()
                .forOrg(TestPersonas.NATIONAL_RELIEF_NETWORK_ID)
                .withTitle(uniqueTitle)
                .withDescription("Draft project that should only appear in global admin listings.")
                .withStatus(ProjectStatus.DRAFT)
                .persist()
        def encodedTitle = URLEncoder.encode(uniqueTitle, StandardCharsets.UTF_8)

        when: "a global admin, an anonymous visitor, and a standard user list projects by title"
        def adminRes = client.exchange(
                asGlobalAdmin(HttpRequest.GET("/projects?title=${encodedTitle}").accept(MediaType.APPLICATION_JSON_TYPE)),
                Map
        )
        def anonRes = client.exchange(
                HttpRequest.GET("/projects?title=${encodedTitle}").accept(MediaType.APPLICATION_JSON_TYPE),
                Map
        )
        def userRes = client.exchange(
                asStandardUser(HttpRequest.GET("/projects?title=${encodedTitle}").accept(MediaType.APPLICATION_JSON_TYPE), TestPersonas.VICTOR_VOLUNTEER),
                Map
        )

        then: "only the global admin sees the draft project"
        def adminIds = (adminRes.body().content as List).collect { it.id.toString() }
        def anonIds = (anonRes.body().content as List).collect { it.id.toString() }
        def userIds = (userRes.body().content as List).collect { it.id.toString() }
        verifyAll {
            adminRes.status == HttpStatus.OK
            adminIds.contains(draft.id().toString())
            anonRes.status == HttpStatus.OK
            !anonIds.contains(draft.id().toString())
            userRes.status == HttpStatus.OK
            !userIds.contains(draft.id().toString())
        }
    }

    def "org member and global admin can view draft shift while outsider and deleted shift are blocked"() {
        given: "a shift on a draft project and a shift on a soft-deleted project"
        def draftProject = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Shift Draft Proj ${UUID.randomUUID()}")
                .withDescription("Draft project with a shift for visibility checks.")
                .withStatus(ProjectStatus.DRAFT)
                .persist()
        def draftShift = dataBuilder.shift()
                .forProject(draftProject)
                .asVirtual()
                .persist()

        def deletedProject = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Shift Deleted Proj ${UUID.randomUUID()}")
                .withDescription("Soft-deleted project with a shift that must remain hidden.")
                .withStatus(ProjectStatus.ACTIVE)
                .deleted()
                .persist()
        def deletedShift = dataBuilder.shift()
                .forProject(deletedProject)
                .asVirtual()
                .persist()

        when: "the org manager and global admin fetch the draft project's shift"
        def managerRes = client.exchange(
                asOrgManager(HttpRequest.GET("/shifts/${draftShift.id()}").accept(MediaType.APPLICATION_JSON_TYPE), TestPersonas.LAURA_LOCAL_MANAGER),
                Shift
        )
        def adminRes = client.exchange(
                asGlobalAdmin(HttpRequest.GET("/shifts/${draftShift.id()}").accept(MediaType.APPLICATION_JSON_TYPE)),
                Shift
        )

        then: "both receive 200 OK"
        verifyAll {
            managerRes.status == HttpStatus.OK
            managerRes.body().id() == draftShift.id()
            adminRes.status == HttpStatus.OK
            adminRes.body().id() == draftShift.id()
        }

        when: "an unrelated standard user fetches the draft project's shift"
        client.exchange(
                asStandardUser(HttpRequest.GET("/shifts/${draftShift.id()}").accept(MediaType.APPLICATION_JSON_TYPE), TestPersonas.VICTOR_VOLUNTEER),
                Shift
        )

        then: "access is blocked with 403 FORBIDDEN"
        def outsiderErr = thrown(HttpClientResponseException)
        outsiderErr.status == HttpStatus.FORBIDDEN

        when: "a user fetches the soft-deleted project's shift"
        client.exchange(
                asOrgManager(HttpRequest.GET("/shifts/${deletedShift.id()}").accept(MediaType.APPLICATION_JSON_TYPE), TestPersonas.LAURA_LOCAL_MANAGER),
                Shift
        )

        then: "404 NOT_FOUND is returned"
        def deletedErr = thrown(HttpClientResponseException)
        deletedErr.status == HttpStatus.NOT_FOUND
    }

    def "listing organizations and projects caps page size at 100"() {
        when: "requesting size=500 on organizations as global admin and on projects"
        def orgRes = client.exchange(
                asGlobalAdmin(HttpRequest.GET("/organizations?size=500").accept(MediaType.APPLICATION_JSON_TYPE)),
                Map
        )
        def projectRes = client.exchange(
                asGlobalAdmin(HttpRequest.GET("/projects?size=500").accept(MediaType.APPLICATION_JSON_TYPE)),
                Map
        )

        then: "both responses cap the returned page size at 100 or fewer"
        def orgBody = orgRes.body()
        def projectBody = projectRes.body()
        verifyAll {
            orgRes.status == HttpStatus.OK
            (orgBody.pageable as Map).size <= 100
            (orgBody.content as List).size() <= 100
            projectRes.status == HttpStatus.OK
            (projectBody.pageable as Map).size <= 100
            (projectBody.content as List).size() <= 100
        }
    }

    def "org manager can update project title with null locations without null pointer error"() {
        given: "an existing draft project owned by Denver Metro Branch"
        def project = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Original Title ${UUID.randomUUID()}")
                .withDescription("Original description that easily exceeds twenty characters.")
                .withStatus(ProjectStatus.DRAFT)
                .persist()
        def updatedTitle = "Updated Title ${UUID.randomUUID()}".toString()
        def payload = [
                organizationId  : TestPersonas.DENVER_METRO_BRANCH_ID.toString(),
                managingRegionId: null,
                title           : updatedTitle,
                description     : "Updated description that still exceeds twenty characters cleanly.",
                projectType     : ProjectType.STANDARD.name(),
                locations       : null,
                boundaries      : [[
                        name       : "Denver Area",
                        coordinates: [
                                [longitude: -105.00, latitude: 39.73],
                                [longitude: -104.98, latitude: 39.73],
                                [longitude: -104.98, latitude: 39.75],
                                [longitude: -105.00, latitude: 39.73]
                        ]
                ]]
        ]

        when: "the org manager sends PUT /projects/{id} with locations set to null"
        def response = client.exchange(
                asOrgManager(HttpRequest.PUT("/projects/${project.id()}", payload).accept(MediaType.APPLICATION_JSON_TYPE), TestPersonas.LAURA_LOCAL_MANAGER),
                Project
        )

        then: "200 OK is returned with the updated title"
        verifyAll(response) {
            status == HttpStatus.OK
            body().id() == project.id()
            body().title() == updatedTitle
        }
    }

    def "org manager can submit draft project to pending and director cannot reject draft or rejected projects"() {
        given: "a virtual draft project and a rejected project"
        def draft = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Submit Draft ${UUID.randomUUID()}")
                .withDescription("Virtual draft project ready to be submitted for review.")
                .withStatus(ProjectStatus.DRAFT)
                .persist()

        def stillDraft = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Unrejected Draft ${UUID.randomUUID()}")
                .withDescription("Draft project that cannot be rejected before submission.")
                .withStatus(ProjectStatus.DRAFT)
                .persist()

        def alreadyRejected = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Already Rejected ${UUID.randomUUID()}")
                .withDescription("Rejected project that cannot be rejected a second time.")
                .withStatus(ProjectStatus.REJECTED)
                .persist()

        when: "the org manager submits the draft project to PENDING"
        def submitCandidate = new Project(
                draft.id(),
                draft.organization(),
                draft.managingRegion(),
                draft.title(),
                draft.description(),
                draft.projectType(),
                ProjectStatus.PENDING,
                draft.createdAt(),
                null,
                null,
                [],
                []
        )
        def submitted = projectService.updateProject(draft.id(), submitCandidate, TestPersonas.LAURA_LOCAL_MANAGER.id)

        then: "its status transitions to PENDING"
        submitted.status() == ProjectStatus.PENDING

        when: "a region director tries to reject the DRAFT project"
        projectService.rejectProject(stillDraft.id(), TestPersonas.DANIEL_COUNTY_DIRECTOR.id)

        then: "400 BAD_REQUEST is thrown"
        def draftErr = thrown(HttpStatusException)
        draftErr.status == HttpStatus.BAD_REQUEST

        when: "a region director tries to reject the already REJECTED project"
        projectService.rejectProject(alreadyRejected.id(), TestPersonas.DANIEL_COUNTY_DIRECTOR.id)

        then: "400 BAD_REQUEST is thrown"
        def rejectedErr = thrown(HttpStatusException)
        rejectedErr.status == HttpStatus.BAD_REQUEST
    }

    def "org manager cannot create shifts on draft projects, deleted projects, or outside locations"() {
        given: "a draft project, a soft-deleted project, and an active project with one assigned location"
        def projectLocation = dataBuilder.location()
                .withName("Assigned Denver Loc")
                .withAddress("100 Main St", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def otherLocation = dataBuilder.location()
                .withName("Unassigned Denver Loc")
                .withAddress("200 Main St", "Denver", "CO", "80202", "US")
                .atPoint(-104.9850, 39.7400)
                .persist()

        def draft = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Draft Shift Guard ${UUID.randomUUID()}")
                .withDescription("Draft project that should not accept new shifts.")
                .withStatus(ProjectStatus.DRAFT)
                .persist()

        def deleted = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Deleted Shift Guard ${UUID.randomUUID()}")
                .withDescription("Soft-deleted project that should return 404 when adding shifts.")
                .withStatus(ProjectStatus.ACTIVE)
                .deleted()
                .persist()

        def activeWithLoc = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Active Loc Guard ${UUID.randomUUID()}")
                .withDescription("Active project bound to a specific location.")
                .withStatus(ProjectStatus.ACTIVE)
                .atLocation(projectLocation)
                .persist()

        def start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
        def end = start.plusHours(2)

        when: "the org manager creates a shift on the DRAFT project"
        client.exchange(
                asOrgManager(HttpRequest.POST("/shifts", new CreateShiftCommand(draft.id(), true, null, start, end, null)), TestPersonas.LAURA_LOCAL_MANAGER),
                Shift
        )

        then: "400 BAD_REQUEST is returned"
        def draftErr = thrown(HttpClientResponseException)
        draftErr.status == HttpStatus.BAD_REQUEST

        when: "the org manager creates a shift on the soft-deleted project"
        client.exchange(
                asOrgManager(HttpRequest.POST("/shifts", new CreateShiftCommand(deleted.id(), true, null, start, end, null)), TestPersonas.LAURA_LOCAL_MANAGER),
                Shift
        )

        then: "404 NOT_FOUND is returned"
        def deletedErr = thrown(HttpClientResponseException)
        deletedErr.status == HttpStatus.NOT_FOUND

        when: "the org manager creates a shift using a location not in project.locations()"
        client.exchange(
                asOrgManager(HttpRequest.POST("/shifts", new CreateShiftCommand(activeWithLoc.id(), false, otherLocation.id(), start, end, null)), TestPersonas.LAURA_LOCAL_MANAGER),
                Shift
        )

        then: "400 BAD_REQUEST is returned"
        def locErr = thrown(HttpClientResponseException)
        locErr.status == HttpStatus.BAD_REQUEST
    }

    def "areAllLocationsInRegion returns false when project has empty locations"() {
        given: "a project with no locations"
        def emptyLocProject = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Virtual Empty Locs")
                .withDescription("Virtual project with an empty locations list.")
                .withStatus(ProjectStatus.DRAFT)
                .build()

        expect: "region location check returns false"
        !authorizationService.areAllLocationsInRegion(emptyLocProject, TestPersonas.DENVER_COUNTY_REGION_ID)
    }

    def "organization service blocks self-parenting, missing parent orgs, and restoring revoked verification"() {
        given: "a revoked organization"
        def org = dataBuilder.organization()
                .withName("Revoked Org ${UUID.randomUUID()}")
                .asPublic()
                .withVerificationStatus(VerificationStatus.REVOKED)
                .persist()

        when: "updating the organization with parentId equal to its own id"
        def selfParentCandidate = new Organization(
                org.id(), org.name(), org.websiteUrl(), org.id(), true, null, null, null
        )
        organizationService.updateOrganization(org.id(), selfParentCandidate, TestPersonas.SARAH_ADMIN.id)

        then: "400 BAD_REQUEST is thrown"
        def selfParentErr = thrown(HttpStatusException)
        selfParentErr.status == HttpStatus.BAD_REQUEST

        when: "updating the organization with a nonexistent parentId"
        def missingParentCandidate = new Organization(
                org.id(), org.name(), org.websiteUrl(), UUID.randomUUID(), true, null, null, null
        )
        organizationService.updateOrganization(org.id(), missingParentCandidate, TestPersonas.SARAH_ADMIN.id)

        then: "400 BAD_REQUEST is thrown"
        def missingParentErr = thrown(HttpStatusException)
        missingParentErr.status == HttpStatus.BAD_REQUEST

        when: "attempting to change verification status from REVOKED to VERIFIED"
        organizationService.updateVerificationStatus(org.id(), VerificationStatus.VERIFIED, "Attempt restore", TestPersonas.SARAH_ADMIN.id)

        then: "400 BAD_REQUEST is thrown"
        def statusErr = thrown(HttpStatusException)
        statusErr.status == HttpStatus.BAD_REQUEST
    }

    def "denver region director cannot move an in-denver location or boundary out to boulder"() {
        given: "a location and boundary inside Denver County"
        def denverLocation = dataBuilder.location()
                .withName("Denver Civic Center")
                .withAddress("1437 Bannock St", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()

        def denverPolygon = dataBuilder.geometryFactory.createPolygon([
                new Coordinate(-105.00, 39.73),
                new Coordinate(-104.98, 39.73),
                new Coordinate(-104.98, 39.75),
                new Coordinate(-105.00, 39.75),
                new Coordinate(-105.00, 39.73)
        ] as Coordinate[])
        def denverBoundary = dataBuilder.boundary()
                .withName("Denver Patch")
                .withGeometry(denverPolygon)
                .persist()

        def moveLocationCmd = new UpdateLocationCommand(
                "Moved to Boulder",
                "1777 Broadway",
                "Boulder",
                "CO",
                "80302",
                "US",
                -105.2705,
                40.0150
        )
        def moveBoundaryCmd = new UpdateBoundaryCommand(
                "Moved to Boulder",
                [
                        new Coordinates(-105.2805, 40.0050),
                        new Coordinates(-105.2605, 40.0050),
                        new Coordinates(-105.2605, 40.0250),
                        new Coordinates(-105.2805, 40.0250),
                        new Coordinates(-105.2805, 40.0050)
                ]
        )

        when: "Denver County director tries to move the Denver location to Boulder"
        client.exchange(
                asRegionDirector(HttpRequest.PUT("/locations/${denverLocation.id()}", moveLocationCmd), TestPersonas.DANIEL_COUNTY_DIRECTOR),
                Map
        )

        then: "access is denied with 403 FORBIDDEN"
        def locErr = thrown(HttpClientResponseException)
        locErr.status == HttpStatus.FORBIDDEN

        when: "Denver County director tries to move the Denver boundary to Boulder"
        client.exchange(
                asRegionDirector(HttpRequest.PUT("/boundaries/${denverBoundary.id()}", moveBoundaryCmd), TestPersonas.DANIEL_COUNTY_DIRECTOR),
                Map
        )

        then: "access is denied with 403 FORBIDDEN"
        def boundaryErr = thrown(HttpClientResponseException)
        boundaryErr.status == HttpStatus.FORBIDDEN
    }
}
