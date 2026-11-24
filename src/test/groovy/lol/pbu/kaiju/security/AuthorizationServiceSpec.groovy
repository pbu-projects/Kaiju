package lol.pbu.kaiju.security

import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.PermissionQueryRepository
import lol.pbu.kaiju.service.ShiftService
import lol.pbu.kaiju.service.UserService
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Polygon

import javax.sql.DataSource
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@MicronautTest(transactional = false)
class AuthorizationServiceSpec extends BaseControllerSpec {

    @Inject
    AuthorizationService service

    @Inject
    PermissionQueryRepository queryRepository

    @Inject
    ShiftService shiftService

    @Inject
    UserService userService

    @Inject
    DataSource dataSource

    UUID orgId
    UUID projectId

    def setup() {
        def org = dataBuilder.organization()
                .withName("Test Org")
                .asPublic()
                .persist()
        orgId = org.id()

        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Security Test Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()
        projectId = project.id()
    }

    User saveUser(UserRole role) {
        dataBuilder.user().withRole(role).persist()
    }

    private Polygon polygonBox(double minLon, double minLat, double maxLon, double maxLat) {
        Coordinate[] coords = [
                new Coordinate(minLon, maxLat),
                new Coordinate(maxLon, maxLat),
                new Coordinate(maxLon, minLat),
                new Coordinate(minLon, minLat),
                new Coordinate(minLon, maxLat)
        ]
        Polygon polygon = dataBuilder.geometryFactory.createPolygon(coords)
        polygon.setSRID(4326)
        return polygon
    }

    private Polygon denverMetroPolygon() {
        Coordinate[] denverCoords = [
                new Coordinate(-105.1099, 39.7891),
                new Coordinate(-104.7432, 39.7912),
                new Coordinate(-104.7528, 39.6158),
                new Coordinate(-105.0536, 39.6137),
                new Coordinate(-105.1099, 39.7891)
        ]
        Polygon polygon = dataBuilder.geometryFactory.createPolygon(denverCoords)
        polygon.setSRID(4326)
        return polygon
    }

    private Polygon denverBoxPolygon() {
        polygonBox(-105.1, 39.6, -104.7, 39.8)
    }

    private Polygon wyomingBoxPolygon() {
        polygonBox(-106.5, 41.0, -106.0, 41.5)
    }

    def "canModifyProject returns true for GLOBAL_ADMIN without any org lookup"() {
        given:
        User admin = saveUser(UserRole.GLOBAL_ADMIN)
        def project = dataBuilder.project()
                .withId(projectId)
                .withoutOrg()
                .withTitle("Title")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        expect:
        service.canModifyProject(admin.id(), project) == true
    }

    def "canModifyProject returns false when user is not org manager"() {
        given:
        User user = saveUser(UserRole.STANDARD_USER)
        def org = dataBuilder.organization()
                .withName("Other Org")
                .asPublic()
                .unverified()
                .persist()

        and: "a project belonging to another org"
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Other Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        expect:
        service.canModifyProject(user.id(), project) == false
    }

    def "canModifyProject returns false when project has no organization"() {
        given:
        User user = saveUser(UserRole.STANDARD_USER)
        def project = dataBuilder.project()
                .withoutOrg()
                .withTitle("Title")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        expect:
        service.canModifyProject(user.id(), project) == false
    }

    def "canModifyProject throws UNAUTHORIZED when user does not exist"() {
        given:
        def project = dataBuilder.project()
                .withId(projectId)
                .withTitle("Title")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        when:
        service.canModifyProject(UUID.randomUUID(), project)

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "authorizeRegionalAdminApproval passes for GLOBAL_ADMIN without any DB jurisdiction check"() {
        given:
        User admin = saveUser(UserRole.GLOBAL_ADMIN)

        when:
        User actor = service.authorizeRegionalAdminApproval(admin.id(), projectId)

        then:
        noExceptionThrown()
        actor.id() == admin.id()
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

    def "authorizeRegionalAdminApproval throws UNAUTHORIZED for unknown user"() {
        when:
        service.authorizeRegionalAdminApproval(UUID.randomUUID(), projectId)

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "canReassignProject returns true for GLOBAL_ADMIN without any jurisdiction check"() {
        given:
        User admin = saveUser(UserRole.GLOBAL_ADMIN)
        def project = dataBuilder.project()
                .withId(projectId)
                .withTitle("Title")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        expect:
        service.canReassignProject(admin.id(), project) == true
    }

    def "canReassignProject returns false for STANDARD_USER even if org manager"() {
        given: "a standard user who is an org manager"
        User user = saveUser(UserRole.STANDARD_USER)
        dataBuilder.organizationUser().asOrgManager(user, orgId).persist()
        def project = dataBuilder.project()
                .withId(projectId)
                .withTitle("Title")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        expect:
        service.canReassignProject(user.id(), project) == false
    }

    def "canReassignProject returns false for REGION_AGENT without jurisdiction"() {
        given: "a regional agent with no jurisdiction over the project"
        User agent = saveUser(UserRole.REGION_AGENT)
        def project = dataBuilder.project()
                .withId(projectId)
                .withTitle("Title")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        expect:
        service.canReassignProject(agent.id(), project) == false
    }

    def "canReassignProject throws UNAUTHORIZED when user does not exist"() {
        given:
        def project = dataBuilder.project()
                .withId(projectId)
                .withTitle("Title")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        when:
        service.canReassignProject(UUID.randomUUID(), project)

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "areAllLocationsInOrgRegion returns false when locations is null or empty"() {
        given:
        def projectNoLocs = dataBuilder.project()
                .withId(projectId)
                .withTitle("Title")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()
        def projectNullLocs = dataBuilder.project()
                .withId(projectId)
                .withTitle("Title")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .withNullLocations()
                .build()

        expect:
        verifyAll {
            service.areAllLocationsInOrgRegion(projectNoLocs, orgId) == false
            service.areAllLocationsInOrgRegion(projectNullLocs, orgId) == false
        }
    }

    def "areAllLocationsInOrgRegion evaluates geographic boundaries correctly"() {
        given: "a region and an organization mapped to it"
        def region = dataBuilder.region()
                .withName("Denver Area")
                .withGeometry(denverMetroPolygon())
                .persist()
        def org = dataBuilder.organization()
                .withName("Geo Org")
                .asPublic()
                .persist()
        dataBuilder.linkOrganizationToRegion(org, region)

        and: "locations inside and outside the region"
        def insideLoc = dataBuilder.location()
                .withName("Inside")
                .withAddress("123 St", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .build()
        def outsideLoc = dataBuilder.location()
                .withName("Outside")
                .withAddress("456 St", "Boulder", "CO", "80302", "US")
                .atPoint(-105.2705, 40.0150)
                .build()

        def projectInside = dataBuilder.project()
                .withTitle("Inside")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.ACTIVE)
                .atLocation(insideLoc)
                .build()
        def projectOutside = dataBuilder.project()
                .withTitle("Outside")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.ACTIVE)
                .atLocation(outsideLoc)
                .build()

        expect:
        verifyAll {
            service.areAllLocationsInOrgRegion(projectInside, org.id()) == true
            service.areAllLocationsInOrgRegion(projectOutside, org.id()) == false
        }
    }

    def "authorizeRegionalAdminApproval allows REGION_DIRECTOR to approve virtual project when org is in their region"() {
        given: "a region and an organization mapped to it"
        def region = dataBuilder.region()
                .withName("Director Region")
                .withGeometry(polygonBox(-105.1, 39.6, -104.7, 39.7))
                .persist()
        def org = dataBuilder.organization()
                .withName("Virtual Org")
                .asPublic()
                .verified()
                .persist()
        dataBuilder.linkOrganizationToRegion(org, region)

        and: "a virtual project belonging to that organization (zero locations in project_locations)"
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Virtual Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        and: "a user who is REGION_DIRECTOR of that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()

        when: "the REGION_DIRECTOR attempts to approve the virtual project"
        User actor = service.authorizeRegionalAdminApproval(director.id(), project.id())

        then: "approval succeeds without throwing an exception"
        noExceptionThrown()
        actor.id() == director.id()
    }

    def "authorizeRegionalAdminApproval throws FORBIDDEN when REGION_DIRECTOR attempts to approve virtual project outside their region"() {
        given: "a region and an organization mapped to a different region"
        def coords = polygonBox(-105.1, 39.6, -104.7, 39.7)
        def directorRegion = dataBuilder.region()
                .withName("Director Region")
                .withGeometry(coords)
                .persist()
        def otherRegion = dataBuilder.region()
                .withName("Other Region")
                .withGeometry(coords)
                .persist()
        def org = dataBuilder.organization()
                .withName("Other Org")
                .asPublic()
                .persist()
        dataBuilder.linkOrganizationToRegion(org, otherRegion)

        and: "a virtual project belonging to that organization"
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Other Virtual Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        and: "a user who is REGION_DIRECTOR of the unrelated region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, directorRegion).persist()

        when:
        service.authorizeRegionalAdminApproval(director.id(), project.id())

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.FORBIDDEN
    }

    def "authorizeRegionalAdminApproval throws FORBIDDEN when REGION_AGENT attempts to approve a virtual project"() {
        given: "a virtual project with 0 locations"
        def project = dataBuilder.project()
                .forOrg(orgId)
                .withTitle("Agent Virtual Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        and: "a REGION_AGENT"
        def agent = saveUser(UserRole.REGION_AGENT)

        when:
        service.authorizeRegionalAdminApproval(agent.id(), project.id())

        then:
        HttpStatusException e = thrown()
        verifyAll(e) {
            status == HttpStatus.FORBIDDEN
            message.contains("virtual")
        }
    }

    def "hasJurisdictionOverAllProjectLocations recognizes REGION_DIRECTOR in addition to REGION_AGENT"() {
        given: "a region, a project with a location inside that region, and a REGION_DIRECTOR"
        def region = dataBuilder.region()
                .withName("Director Loc Region")
                .withGeometry(denverBoxPolygon())
                .persist()
        def loc = dataBuilder.location()
                .withName("Loc")
                .withAddress("St", "City", null, null, "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def project = dataBuilder.project()
                .forOrg(orgId)
                .withTitle("Loc Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(loc)
                .persist()

        and: "a REGION_DIRECTOR assigned to that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()

        expect:
        verifyAll {
            queryRepository.hasJurisdictionOverAllProjectLocations(director.id(), project.id()) == true
            service.canReassignProject(director.id(), project) == true
        }
    }

    def "canModifyProject allows REGION_DIRECTOR to modify a virtual project in their region"() {
        given: "a region, verified organization in that region, and virtual project"
        def region = dataBuilder.region()
                .withName("Mod Region")
                .withGeometry(denverBoxPolygon())
                .persist()
        def org = dataBuilder.organization()
                .withName("Mod Org")
                .asPublic()
                .verified()
                .persist()
        dataBuilder.linkOrganizationToRegion(org, region)
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Virtual Mod Proj")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        and: "a REGION_DIRECTOR of that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()

        expect:
        service.canModifyProject(director.id(), project) == true
    }

    def "canModifyProject denies REGION_DIRECTOR on virtual project outside their region"() {
        given: "a region, organization in a different region, and virtual project"
        def dirRegion = dataBuilder.region()
                .withName("Dir Reg")
                .withGeometry(denverBoxPolygon())
                .persist()
        def otherRegion = dataBuilder.region()
                .withName("Other Reg")
                .withGeometry(denverBoxPolygon())
                .persist()
        def org = dataBuilder.organization()
                .withName("Other Org")
                .asPublic()
                .verified()
                .persist()
        dataBuilder.linkOrganizationToRegion(org, otherRegion)
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Virtual Mod Proj")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        and: "a REGION_DIRECTOR of Dir Reg"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, dirRegion).persist()

        expect:
        service.canModifyProject(director.id(), project) == false
    }

    def "isVirtualProjectInDirectorJurisdiction enforces managing_region_id precedence over organization_regions"() {
        given: "two regions A and B"
        def regA = dataBuilder.region()
                .withName("Region A")
                .withGeometry(denverBoxPolygon())
                .persist()
        def regB = dataBuilder.region()
                .withName("Region B")
                .withGeometry(denverBoxPolygon())
                .persist()

        and: "an organization with presence in both Region A and Region B"
        def orgMulti = dataBuilder.organization()
                .withName("Multi Org")
                .asPublic()
                .verified()
                .persist()
        dataBuilder.linkOrganizationToRegion(orgMulti, regA)
        dataBuilder.linkOrganizationToRegion(orgMulti, regB)

        and: "a virtual project explicitly assigned to Region A via managing_region_id"
        def projA = dataBuilder.project()
                .forOrg(orgMulti)
                .inRegion(regA)
                .withTitle("Project specifically in Region A")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        and: "directors for Region A and Region B"
        def dirA = saveUser(UserRole.REGION_DIRECTOR)
        def dirB = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(dirA, regA).persist()
        dataBuilder.regionUser().asRegionDirector(dirB, regB).persist()

        expect: "Director A has jurisdiction while Director B is denied because managing_region_id takes precedence"
        verifyAll {
            queryRepository.isVirtualProjectInDirectorJurisdiction(dirA.id(), projA.id()) == true
            queryRepository.isVirtualProjectInDirectorJurisdiction(dirB.id(), projA.id()) == false
        }
    }

    def "isVirtualProjectInDirectorJurisdiction rejects unverified organizations"() {
        given: "a region and an UNVERIFIED organization in that region"
        def region = dataBuilder.region()
                .withName("Unverified Reg")
                .withGeometry(denverBoxPolygon())
                .persist()
        def unverifiedOrg = dataBuilder.organization()
                .withName("Unverified Org")
                .asPublic()
                .unverified()
                .persist()
        dataBuilder.linkOrganizationToRegion(unverifiedOrg, region)
        def project = dataBuilder.project()
                .forOrg(unverifiedOrg)
                .withTitle("Unverified Org Proj")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        and: "a director of that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()

        expect: "unverified organization virtual project cannot be approved by director"
        queryRepository.isVirtualProjectInDirectorJurisdiction(director.id(), project.id()) == false
    }

    def "canModifyProject allows REGION_DIRECTOR to modify a physical project with locations inside their region"() {
        given: "a region, verified org, location, and physical project"
        def region = dataBuilder.region()
                .withName("Dir Phys Mod Reg")
                .withGeometry(denverBoxPolygon())
                .persist()
        def org = dataBuilder.organization()
                .withName("Phys Mod Org")
                .asPublic()
                .verified()
                .persist()
        def loc = dataBuilder.location()
                .withName("Loc")
                .withAddress("St", "Denver", null, null, "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Phys Mod Proj")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(loc)
                .persist()

        and: "a REGION_DIRECTOR assigned to that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()

        expect:
        service.canModifyProject(director.id(), project) == true
    }

    def "canReassignProject allows REGION_DIRECTOR to reassign a virtual project in their region"() {
        given: "a region, verified org, and virtual project"
        def region = dataBuilder.region()
                .withName("Dir VReassign Reg")
                .withGeometry(denverBoxPolygon())
                .persist()
        def org = dataBuilder.organization()
                .withName("VReassign Org")
                .asPublic()
                .verified()
                .persist()
        dataBuilder.linkOrganizationToRegion(org, region)
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("VReassign Proj")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        and: "a REGION_DIRECTOR of that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()

        expect:
        service.canReassignProject(director.id(), project) == true
    }

    def "authorizeRegionalAdminApproval throws FORBIDDEN for REGION_DIRECTOR on physical project outside their region"() {
        given: "a region, an out-of-region location, and a physical project"
        def region = dataBuilder.region()
                .withName("Director Reg")
                .withGeometry(denverBoxPolygon())
                .persist()
        def org = dataBuilder.organization()
                .withName("Org")
                .asPublic()
                .verified()
                .persist()
        def loc = dataBuilder.location()
                .withName("Far Loc")
                .withAddress("St", "Boulder", null, null, "US")
                .atPoint(-105.2705, 40.0150)
                .persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Far Phys Proj")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(loc)
                .persist()

        and: "a REGION_DIRECTOR assigned to Denver region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()

        when: "attempting to approve out-of-region physical project"
        service.authorizeRegionalAdminApproval(director.id(), project.id())

        then: "a 403 Forbidden is thrown"
        HttpStatusException e = thrown()
        e.status == HttpStatus.FORBIDDEN
    }

    def "areAllLocationsInOrgRegion batches multiple locations in single query"() {
        given: "an organization linked to a region"
        def region = dataBuilder.region()
                .withName("Batch Region")
                .withGeometry(denverBoxPolygon())
                .persist()
        def org = dataBuilder.organization()
                .withName("Batch Org")
                .asPublic()
                .verified()
                .persist()
        dataBuilder.linkOrganizationToRegion(org, region)

        and: "multiple points within the region"
        def loc1 = dataBuilder.location().withName("L1").withAddress("A1", "C1", null, null, "US").atPoint(-105.0, 39.7).build()
        def loc2 = dataBuilder.location().withName("L2").withAddress("A2", "C2", null, null, "US").atPoint(-104.9, 39.7).build()
        def loc3 = dataBuilder.location().withName("L3").withAddress("A3", "C3", null, null, "US").atPoint(-104.8, 39.7).build()

        def projectAllIn = dataBuilder.project()
                .withTitle("P1")
                .withDescription("D")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocations([loc1, loc2, loc3])
                .build()

        and: "one point outside the region"
        def locOut = dataBuilder.location().withName("LOut").withAddress("A4", "C4", null, null, "US").atPoint(-106.0, 41.0).build()
        def projectWithOut = dataBuilder.project()
                .withTitle("P2")
                .withDescription("D")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocations([loc1, locOut])
                .build()

        expect:
        verifyAll {
            service.areAllLocationsInOrgRegion(projectAllIn, org.id()) == true
            service.areAllLocationsInOrgRegion(projectWithOut, org.id()) == false
        }
    }

    def "canAssignManagingRegion evaluates roles and organization presence"() {
        given: "a region and organization"
        def region = dataBuilder.region()
                .withName("Assign Region")
                .withGeometry(denverBoxPolygon())
                .persist()
        def org = dataBuilder.organization()
                .withName("Assign Org")
                .asPublic()
                .verified()
                .persist()
        dataBuilder.linkOrganizationToRegion(org, region)

        and: "users with various roles"
        def admin = saveUser(UserRole.GLOBAL_ADMIN)
        def standardUser = saveUser(UserRole.STANDARD_USER)

        def orgManager = saveUser(UserRole.STANDARD_USER)
        dataBuilder.organizationUser().asOrgManager(orgManager, org).persist()

        def regionalAgent = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(regionalAgent, region).persist()

        expect:
        verifyAll {
            service.canAssignManagingRegion(admin.id(), org.id(), region.id()) == true
            service.canAssignManagingRegion(orgManager.id(), org.id(), region.id()) == true
            service.canAssignManagingRegion(regionalAgent.id(), org.id(), region.id()) == true
            service.canAssignManagingRegion(standardUser.id(), org.id(), region.id()) == false
        }

        when: "canAssignManagingRegion is called with a missing actor"
        service.canAssignManagingRegion(UUID.randomUUID(), org.id(), region.id())

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.UNAUTHORIZED
    }

    private void addProjectSponsor(UUID userId, UUID targetProjectId) {
        DelegatingDataSource.unwrapDataSource(dataSource).connection.withCloseable { conn ->
            conn.prepareStatement("INSERT INTO project_users (user_id, project_id, role) VALUES (?, ?, 'PROJECT_SPONSOR')").withCloseable { ps ->
                ps.setObject(1, userId)
                ps.setObject(2, targetProjectId)
                ps.executeUpdate()
            }
        }
    }

    def "canViewProject, canViewShift, and authorizeShiftView enforce visibility and handle null project"() {
        given: "an org, a region, an active project, and a pending project with a location"
        def region = dataBuilder.region()
                .withName("View Region")
                .withGeometry(denverBoxPolygon())
                .persist()
        def org = dataBuilder.organization()
                .withName("View Org")
                .asPublic()
                .verified()
                .persist()
        def loc = dataBuilder.location()
                .withName("View Loc")
                .withAddress("St", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def activeProject = dataBuilder.project()
                .forOrg(org)
                .withTitle("Active Proj")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.ACTIVE)
                .atLocation(loc)
                .persist()
        def pendingProject = dataBuilder.project()
                .forOrg(org)
                .withTitle("Pending Proj")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(loc)
                .persist()

        and: "users with various roles"
        def admin = saveUser(UserRole.GLOBAL_ADMIN)
        def outsider = saveUser(UserRole.STANDARD_USER)
        def orgMember = saveUser(UserRole.STANDARD_USER)
        dataBuilder.organizationUser().asOrgMember(orgMember, org).persist()
        def sponsor = saveUser(UserRole.STANDARD_USER)
        addProjectSponsor(sponsor.id(), pendingProject.id())
        def regionalAgent = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(regionalAgent, region).persist()

        def start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
        def end = start.plusHours(2)
        def activeShift = new Shift(null, activeProject, true, null, start, end, [])
        def pendingShift = new Shift(null, pendingProject, true, null, start, end, [])
        def orphanShift = new Shift(null, null, true, null, start, end, [])

        expect:
        verifyAll {
            service.canViewProject(null, activeProject) == true
            service.canViewProject(null, pendingProject) == false
            service.canViewProject(outsider.id(), pendingProject) == false
            service.canViewProject(admin.id(), pendingProject) == true
            service.canViewProject(orgMember.id(), pendingProject) == true
            service.canViewProject(sponsor.id(), pendingProject) == true
            service.canViewProject(regionalAgent.id(), pendingProject) == true
            service.canViewShift(null, activeShift) == true
            service.canViewShift(outsider.id(), pendingShift) == false
            service.canViewShift(sponsor.id(), pendingShift) == true
            service.canViewShift(admin.id(), orphanShift) == false
        }

        when: "authorizeShiftView passes for sponsor and fails with 403 for outsider"
        service.authorizeShiftView(sponsor.id(), pendingShift)
        service.authorizeShiftView(outsider.id(), pendingShift)

        then:
        HttpStatusException shiftViewEx = thrown()
        shiftViewEx.status == HttpStatus.FORBIDDEN

        when: "authorizeShiftView is called with null userId on a pending shift"
        service.authorizeShiftView(null, pendingShift)

        then:
        HttpStatusException unauthShiftEx = thrown()
        unauthShiftEx.status == HttpStatus.UNAUTHORIZED

        when: "canViewProject, canViewShift, and authorizeShiftView are called for an active resource with a missing actor"
        service.canViewProject(UUID.randomUUID(), activeProject)

        then:
        HttpStatusException missingActiveProjEx = thrown()
        missingActiveProjEx.status == HttpStatus.UNAUTHORIZED

        when: "canViewShift is called for an active shift with a missing actor"
        service.canViewShift(UUID.randomUUID(), activeShift)

        then:
        HttpStatusException missingActiveShiftEx = thrown()
        missingActiveShiftEx.status == HttpStatus.UNAUTHORIZED

        when: "authorizeShiftView is called for an active shift with a missing actor"
        service.authorizeShiftView(UUID.randomUUID(), activeShift)

        then:
        HttpStatusException missingAuthShiftEx = thrown()
        missingAuthShiftEx.status == HttpStatus.UNAUTHORIZED

        when: "canViewProject is called for a pending project with a missing actor"
        service.canViewProject(UUID.randomUUID(), pendingProject)

        then:
        HttpStatusException missingActorEx = thrown()
        missingActorEx.status == HttpStatus.UNAUTHORIZED
    }

    def "DefaultShiftService allows Regional Admins with jurisdiction and Project Sponsors while denying outsiders"() {
        given: "two regions, an active project in Region A, and various users"
        def regionA = dataBuilder.region()
                .withName("Shift Region A")
                .withGeometry(denverBoxPolygon())
                .persist()
        def regionB = dataBuilder.region()
                .withName("Shift Region B")
                .withGeometry(wyomingBoxPolygon())
                .persist()
        def org = dataBuilder.organization()
                .withName("Shift Org")
                .asPublic()
                .verified()
                .persist()
        def locA = dataBuilder.location()
                .withName("Shift Loc A")
                .withAddress("123 Main", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Shift Auth Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.ACTIVE)
                .atLocation(locA)
                .persist()

        def inRegionAgent = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(inRegionAgent, regionA).persist()
        def outOfRegionAgent = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(outOfRegionAgent, regionB).persist()
        def sponsor = saveUser(UserRole.STANDARD_USER)
        addProjectSponsor(sponsor.id(), project.id())
        def outsider = saveUser(UserRole.STANDARD_USER)

        def start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
        def end = start.plusHours(3)
        def candidateShift = new Shift(null, project, false, locA, start, end, [])

        when: "in-region agent creates a shift"
        def createdByAgent = track(shiftService.createShift(candidateShift, inRegionAgent.id()))

        then:
        createdByAgent.id() != null

        when: "project sponsor updates the shift"
        def updatedShift = shiftService.updateShift(
                createdByAgent.id(),
                new Shift(createdByAgent.id(), project, false, locA, start.plusHours(1), end.plusHours(1), []),
                sponsor.id()
        )

        then:
        updatedShift.startTime() == start.plusHours(1)

        when: "out-of-region agent tries to create a shift"
        shiftService.createShift(candidateShift, outOfRegionAgent.id())

        then:
        HttpStatusException outEx = thrown()
        outEx.status == HttpStatus.FORBIDDEN

        when: "non-sponsor standard user tries to delete the shift"
        shiftService.deleteShift(createdByAgent.id(), outsider.id())

        then:
        HttpStatusException outsiderEx = thrown()
        outsiderEx.status == HttpStatus.FORBIDDEN

        when: "sponsor deletes the shift"
        shiftService.deleteShift(createdByAgent.id(), sponsor.id())

        then:
        noExceptionThrown()

        when: "a missing actor attempts create, update, or delete on ShiftService"
        shiftService.createShift(candidateShift, UUID.randomUUID())

        then:
        HttpStatusException createMissingEx = thrown()
        createMissingEx.status == HttpStatus.UNAUTHORIZED

        when: "a missing actor attempts updateShift"
        shiftService.updateShift(UUID.randomUUID(), candidateShift, UUID.randomUUID())

        then:
        HttpStatusException updateMissingEx = thrown()
        updateMissingEx.status == HttpStatus.UNAUTHORIZED

        when: "a missing actor attempts deleteShift"
        shiftService.deleteShift(UUID.randomUUID(), UUID.randomUUID())

        then:
        HttpStatusException deleteMissingEx = thrown()
        deleteMissingEx.status == HttpStatus.UNAUTHORIZED
    }

    def "DefaultUserService enforces authorizeSelfOrUserManager and authorizeUserManager"() {
        given: "two standard users and a user manager"
        def alice = saveUser(UserRole.STANDARD_USER)
        def bob = saveUser(UserRole.STANDARD_USER)
        def admin = saveUser(UserRole.GLOBAL_ADMIN)

        expect: "self and admin can read user, outsider cannot"
        verifyAll {
            userService.getCurrentUser(alice.id()).id() == alice.id()
            userService.getUserById(alice.id(), alice.id()).isPresent()
            userService.getUserById(alice.id(), admin.id()).isPresent()
        }

        when: "bob tries to read alice"
        userService.getUserById(alice.id(), bob.id())

        then:
        HttpStatusException readEx = thrown()
        readEx.status == HttpStatus.FORBIDDEN

        when: "bob tries to create a user"
        userService.createUser(new User(null, "new@example.com", UserRole.STANDARD_USER, OffsetDateTime.now(ZoneOffset.UTC)), bob.id())

        then:
        HttpStatusException createEx = thrown()
        createEx.status == HttpStatus.FORBIDDEN

        when: "bob tries to update alice"
        userService.updateUser(alice.id(), new User(alice.id(), "hacked@example.com", null, null), bob.id())

        then:
        HttpStatusException updateEx = thrown()
        updateEx.status == HttpStatus.FORBIDDEN

        when: "bob tries to delete alice"
        userService.deleteUser(alice.id(), bob.id())

        then:
        HttpStatusException deleteEx = thrown()
        deleteEx.status == HttpStatus.FORBIDDEN
    }

    def "AuthorizationService enforces org, region, location, and boundary authorization without existence oracles"() {
        given: "regions, orgs, location, boundary, and users"
        def polygon = denverBoxPolygon()
        def region = dataBuilder.region().withName("Auth Region").withGeometry(polygon).persist()
        def otherRegion = dataBuilder.region().withName("Other Auth Region").withGeometry(wyomingBoxPolygon()).persist()
        def privateOrg = dataBuilder.organization().withName("Private Org").asPrivate().unverified().persist()
        def publicOrg = dataBuilder.organization().withName("Public Org").asPublic().verified().persist()
        def loc = dataBuilder.location()
                .withName("Auth Loc")
                .withAddress("123 Main", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def boundary = dataBuilder.boundary().withName("Auth Boundary").withGeometry(polygon).persist()

        def admin = saveUser(UserRole.GLOBAL_ADMIN)
        def outsider = saveUser(UserRole.STANDARD_USER)
        def orgManager = saveUser(UserRole.STANDARD_USER)
        dataBuilder.organizationUser().asOrgManager(orgManager, privateOrg).persist()
        def orgAdmin = saveUser(UserRole.STANDARD_USER)
        dataBuilder.organizationUser().asOrgAdmin(orgAdmin, privateOrg).persist()
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()
        def agent = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(agent, region).persist()
        def otherDirector = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(otherDirector, otherRegion).persist()

        def insidePoint = dataBuilder.geometryFactory.createPoint(new Coordinate(-104.9903, 39.7392))
        insidePoint.setSRID(4326)
        def outsidePoint = dataBuilder.geometryFactory.createPoint(new Coordinate(-106.0, 41.0))
        outsidePoint.setSRID(4326)

        expect: "organization view and verification checks"
        verifyAll {
            service.canViewOrganization(null, publicOrg) == true
            service.canViewOrganization(null, privateOrg) == false
            service.canViewOrganization(outsider.id(), privateOrg) == false
            service.canViewOrganization(orgManager.id(), privateOrg) == true
            service.canViewOrganization(admin.id(), privateOrg) == true
        }

        when: "authorized callers invoke organization, region, location, and boundary guards"
        service.authorizeOrganizationView(null, publicOrg)
        service.authorizeOrganizationView(orgManager.id(), privateOrg)
        service.authorizeOrganizationView(admin.id(), privateOrg)
        service.authorizeOrganizationUpdate(orgManager.id(), privateOrg.id())
        service.authorizeOrganizationUpdate(admin.id(), privateOrg.id())
        service.authorizeOrganizationAdmin(orgAdmin.id(), privateOrg.id())
        User verifiedActor = service.authorizeOrganizationVerification(admin.id())
        service.authorizeRegionDirector(director.id(), region.id())
        service.authorizeRegionDirector(admin.id(), region.id())
        service.authorizeLocationCreate(director.id(), insidePoint)
        service.authorizeLocationUpdate(director.id(), loc.id(), insidePoint)
        service.authorizeLocationDelete(director.id(), loc.id())
        service.authorizeLocationDelete(admin.id(), UUID.randomUUID())
        service.authorizeBoundaryCreate(director.id(), polygon)
        service.authorizeBoundaryUpdate(director.id(), boundary.id(), polygon)
        service.authorizeBoundaryDelete(director.id(), boundary.id())
        service.authorizeBoundaryDelete(admin.id(), UUID.randomUUID())

        then:
        noExceptionThrown()
        verifiedActor.id() == admin.id()

        when: "outsider attempts to view private org via authorizeOrganizationView"
        service.authorizeOrganizationView(outsider.id(), privateOrg)

        then:
        HttpStatusException orgViewEx = thrown()
        orgViewEx.status == HttpStatus.FORBIDDEN

        when: "unauthenticated caller with null userId attempts to view private org via authorizeOrganizationView"
        service.authorizeOrganizationView(null, privateOrg)

        then:
        HttpStatusException unauthOrgViewEx = thrown()
        unauthOrgViewEx.status == HttpStatus.UNAUTHORIZED

        when: "deleted or missing actor attempts to view a public org via canViewOrganization or authorizeOrganizationView"
        service.canViewOrganization(UUID.randomUUID(), publicOrg)

        then:
        HttpStatusException missingOrgCanViewEx = thrown()
        missingOrgCanViewEx.status == HttpStatus.UNAUTHORIZED

        when: "deleted or missing actor calls authorizeOrganizationView on a public org"
        service.authorizeOrganizationView(UUID.randomUUID(), publicOrg)

        then:
        HttpStatusException missingOrgAuthViewEx = thrown()
        missingOrgAuthViewEx.status == HttpStatus.UNAUTHORIZED

        when: "outsider attempts org update"
        service.authorizeOrganizationUpdate(outsider.id(), privateOrg.id())

        then:
        HttpStatusException e1 = thrown()
        e1.status == HttpStatus.FORBIDDEN

        when: "orgManager (not orgAdmin) attempts org admin action"
        service.authorizeOrganizationAdmin(orgManager.id(), privateOrg.id())

        then:
        HttpStatusException e2 = thrown()
        e2.status == HttpStatus.FORBIDDEN

        when: "outsider attempts org verification"
        service.authorizeOrganizationVerification(outsider.id())

        then:
        HttpStatusException e3 = thrown()
        e3.status == HttpStatus.FORBIDDEN

        when: "region agent attempts region director action"
        service.authorizeRegionDirector(agent.id(), region.id())

        then:
        HttpStatusException agentDirEx = thrown()
        agentDirEx.status == HttpStatus.FORBIDDEN

        when: "cross-region director attempts region director action"
        service.authorizeRegionDirector(otherDirector.id(), region.id())

        then:
        HttpStatusException crossDirEx = thrown()
        crossDirEx.status == HttpStatus.FORBIDDEN

        when: "director attempts location create outside region"
        service.authorizeLocationCreate(director.id(), outsidePoint)

        then:
        HttpStatusException e5 = thrown()
        e5.status == HttpStatus.FORBIDDEN

        when: "director attempts to delete missing location (eliminated existence oracle)"
        service.authorizeLocationDelete(director.id(), UUID.randomUUID())

        then:
        HttpStatusException e6 = thrown()
        e6.status == HttpStatus.FORBIDDEN

        when: "director attempts to delete missing boundary (eliminated existence oracle)"
        service.authorizeBoundaryDelete(director.id(), UUID.randomUUID())

        then:
        HttpStatusException e7 = thrown()
        e7.status == HttpStatus.FORBIDDEN
    }

    def "AuthorizationService rejects out-of-region and null location and boundary changes"() {
        given: "a region, a director, an in-region location/boundary, and an out-of-region location/boundary"
        def inPolygon = denverBoxPolygon()
        def outPolygon = wyomingBoxPolygon()

        def region = dataBuilder.region().withName("Gap Region").withGeometry(inPolygon).persist()
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()

        def inLoc = dataBuilder.location()
                .withName("In Loc")
                .withAddress("123 Main", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def outLoc = dataBuilder.location()
                .withName("Out Loc")
                .withAddress("456 Far", "Laramie", "WY", "82070", "US")
                .atPoint(-106.2, 41.2)
                .persist()

        def inBoundary = dataBuilder.boundary().withName("In Boundary").withGeometry(inPolygon).persist()
        def outBoundary = dataBuilder.boundary().withName("Out Boundary").withGeometry(outPolygon).persist()

        def outsidePoint = dataBuilder.geometryFactory.createPoint(new Coordinate(-106.2, 41.2))
        outsidePoint.setSRID(4326)

        when: "director updates an out-of-region location"
        service.authorizeLocationUpdate(director.id(), outLoc.id(), outsidePoint)

        then:
        HttpStatusException locOutEx = thrown()
        locOutEx.status == HttpStatus.FORBIDDEN

        when: "director moves an in-region location outside their region"
        service.authorizeLocationUpdate(director.id(), inLoc.id(), outsidePoint)

        then:
        HttpStatusException locMoveEx = thrown()
        locMoveEx.status == HttpStatus.FORBIDDEN

        when: "director updates a location with a null point"
        service.authorizeLocationUpdate(director.id(), inLoc.id(), null)

        then:
        HttpStatusException locNullEx = thrown()
        locNullEx.status == HttpStatus.FORBIDDEN

        when: "director deletes an existing location outside their region"
        service.authorizeLocationDelete(director.id(), outLoc.id())

        then:
        HttpStatusException locDelEx = thrown()
        locDelEx.status == HttpStatus.FORBIDDEN

        when: "director creates a boundary outside their region"
        service.authorizeBoundaryCreate(director.id(), outPolygon)

        then:
        HttpStatusException bndCreateOutEx = thrown()
        bndCreateOutEx.status == HttpStatus.FORBIDDEN

        when: "director creates a boundary with null geometry"
        service.authorizeBoundaryCreate(director.id(), null)

        then:
        HttpStatusException bndCreateNullEx = thrown()
        bndCreateNullEx.status == HttpStatus.FORBIDDEN

        when: "director updates an out-of-region boundary"
        service.authorizeBoundaryUpdate(director.id(), outBoundary.id(), outPolygon)

        then:
        HttpStatusException bndUpdateOutEx = thrown()
        bndUpdateOutEx.status == HttpStatus.FORBIDDEN

        when: "director moves an in-region boundary outside their region"
        service.authorizeBoundaryUpdate(director.id(), inBoundary.id(), outPolygon)

        then:
        HttpStatusException bndMoveEx = thrown()
        bndMoveEx.status == HttpStatus.FORBIDDEN

        when: "director updates a boundary with null geometry"
        service.authorizeBoundaryUpdate(director.id(), inBoundary.id(), null)

        then:
        HttpStatusException bndUpdateNullEx = thrown()
        bndUpdateNullEx.status == HttpStatus.FORBIDDEN

        when: "director deletes an existing boundary outside their region"
        service.authorizeBoundaryDelete(director.id(), outBoundary.id())

        then:
        HttpStatusException bndDelEx = thrown()
        bndDelEx.status == HttpStatus.FORBIDDEN
    }

    def "AuthorizationService handles allowOrgManageUsers, expired org verification, project visibility edges, and missing actors"() {
        given: "regions, orgs, projects, and users"
        def regionA = dataBuilder.region()
                .withName("Edge Region A")
                .withGeometry(denverBoxPolygon())
                .persist()
        def regionB = dataBuilder.region()
                .withName("Edge Region B")
                .withGeometry(wyomingBoxPolygon())
                .persist()

        def admin = saveUser(UserRole.GLOBAL_ADMIN)
        def orgManager = saveUser(UserRole.STANDARD_USER)
        def orgAdmin = saveUser(UserRole.STANDARD_USER)
        def outsider = saveUser(UserRole.STANDARD_USER)
        def agentA = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(agentA, regionA).persist()
        def agentB = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(agentB, regionB).persist()

        def privateOrg = dataBuilder.organization().withName("Edge Private Org").asPrivate().unverified().persist()
        dataBuilder.organizationUser().asOrgManager(orgManager, privateOrg).persist()
        dataBuilder.organizationUser().asOrgAdmin(orgAdmin, privateOrg).persist()

        def expiredOrg = dataBuilder.organization()
                .withName("Expired Public Org")
                .asPublic()
                .verified()
                .withVerificationExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                .persist()

        def deletedProject = dataBuilder.project()
                .forOrg(privateOrg)
                .withTitle("Deleted Active Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.ACTIVE)
                .deleted(admin)
                .persist()

        def draftProject = dataBuilder.project()
                .forOrg(privateOrg)
                .withTitle("Draft Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.DRAFT)
                .persist()

        def virtualManagedProject = dataBuilder.project()
                .forOrg(privateOrg)
                .inRegion(regionA)
                .withTitle("Virtual Managed Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        expect: "project and organization visibility edge cases"
        verifyAll {
            service.canViewProject(null, deletedProject) == false
            service.canViewProject(admin.id(), deletedProject) == false
            service.canViewProject(null, draftProject) == false
            service.canViewProject(outsider.id(), draftProject) == false
            service.canViewProject(orgManager.id(), draftProject) == true
            service.canViewProject(admin.id(), draftProject) == true
            service.canViewProject(agentA.id(), virtualManagedProject) == true
            service.canViewProject(agentB.id(), virtualManagedProject) == false
            service.canViewOrganization(null, expiredOrg) == false
            service.canViewOrganization(outsider.id(), expiredOrg) == false
            service.canViewOrganization(admin.id(), expiredOrg) == true
            service.isSystemAdmin(null) == false
            service.isSystemAdmin(outsider.id()) == false
            service.isSystemAdmin(admin.id()) == true
        }

        when: "authorizeOrganizationAdmin is called with allowOrgManageUsers = true for ORG_ADMIN"
        service.authorizeOrganizationAdmin(orgAdmin.id(), privateOrg.id(), true)

        then:
        noExceptionThrown()

        when: "authorizeOrganizationAdmin is called with allowOrgManageUsers = true for orgManager"
        service.authorizeOrganizationAdmin(orgManager.id(), privateOrg.id(), true)

        then:
        HttpStatusException orgAdminEx = thrown()
        orgAdminEx.status == HttpStatus.FORBIDDEN

        when: "canManageShift is called with a missing actor"
        service.canManageShift(UUID.randomUUID(), virtualManagedProject)

        then:
        HttpStatusException shiftActorEx = thrown()
        shiftActorEx.status == HttpStatus.UNAUTHORIZED

        when: "authorizeOrganizationUpdate is called with a missing actor"
        service.authorizeOrganizationUpdate(UUID.randomUUID(), privateOrg.id())

        then:
        HttpStatusException orgUpdActorEx = thrown()
        orgUpdActorEx.status == HttpStatus.UNAUTHORIZED

        when: "authorizeOrganizationVerification is called with a missing actor"
        service.authorizeOrganizationVerification(UUID.randomUUID())

        then:
        HttpStatusException orgVerActorEx = thrown()
        orgVerActorEx.status == HttpStatus.UNAUTHORIZED

        when: "authorizeRegionDirector is called with a missing actor"
        service.authorizeRegionDirector(UUID.randomUUID(), regionA.id())

        then:
        HttpStatusException regDirActorEx = thrown()
        regDirActorEx.status == HttpStatus.UNAUTHORIZED

        when: "isSystemAdmin is called with a non-null missing actor"
        service.isSystemAdmin(UUID.randomUUID())

        then:
        HttpStatusException sysAdminActorEx = thrown()
        sysAdminActorEx.status == HttpStatus.UNAUTHORIZED

        when: "evaluateProjectCreationByUser is called with a missing actor"
        service.evaluateProjectCreationByUser(UUID.randomUUID(), draftProject)

        then:
        HttpStatusException projCreateActorEx = thrown()
        projCreateActorEx.status == HttpStatus.UNAUTHORIZED
    }

    def "AuthorizationService verifies actor existence in authorizeSelfOrUserManager, authorizeOrganizationUserView, and authorizeRegionUserView"() {
        given: "seeded personas and an unseeded missing actor ID"
        UUID missingActorId = UUID.randomUUID()

        when: "valid self, admin, and membership views succeed"
        service.authorizeSelfOrUserManager(TestPersonas.VICTOR_VOLUNTEER.id, TestPersonas.VICTOR_VOLUNTEER.id)
        service.authorizeSelfOrUserManager(TestPersonas.SARAH_ADMIN.id, TestPersonas.VICTOR_VOLUNTEER.id)
        service.authorizeOrganizationUserView(TestPersonas.LUKE_LOCAL_MEMBER.id, TestPersonas.LUKE_LOCAL_MEMBER.id, TestPersonas.DENVER_METRO_BRANCH_ID)
        service.authorizeOrganizationUserView(TestPersonas.LEO_LOCAL_ADMIN.id, TestPersonas.LUKE_LOCAL_MEMBER.id, TestPersonas.DENVER_METRO_BRANCH_ID)
        service.authorizeRegionUserView(TestPersonas.CONNIE_COUNTY_AGENT.id, TestPersonas.CONNIE_COUNTY_AGENT.id, TestPersonas.DENVER_COUNTY_REGION_ID)
        service.authorizeRegionUserView(TestPersonas.DANIEL_COUNTY_DIRECTOR.id, TestPersonas.CONNIE_COUNTY_AGENT.id, TestPersonas.DENVER_COUNTY_REGION_ID)

        then:
        noExceptionThrown()

        when: "a deleted/missing actor calls authorizeSelfOrUserManager matching their own ID"
        service.authorizeSelfOrUserManager(missingActorId, missingActorId)

        then:
        HttpStatusException selfMissingEx = thrown()
        selfMissingEx.status == HttpStatus.UNAUTHORIZED

        when: "a deleted/missing actor calls authorizeOrganizationUserView matching their own ID"
        service.authorizeOrganizationUserView(missingActorId, missingActorId, TestPersonas.DENVER_METRO_BRANCH_ID)

        then:
        HttpStatusException orgSelfMissingEx = thrown()
        orgSelfMissingEx.status == HttpStatus.UNAUTHORIZED

        when: "a deleted/missing actor calls authorizeRegionUserView matching their own ID"
        service.authorizeRegionUserView(missingActorId, missingActorId, TestPersonas.DENVER_COUNTY_REGION_ID)

        then:
        HttpStatusException regSelfMissingEx = thrown()
        regSelfMissingEx.status == HttpStatus.UNAUTHORIZED

        when: "a cross-org admin calls authorizeOrganizationUserView"
        service.authorizeOrganizationUserView(TestPersonas.URSULA_UTAH_ADMIN.id, TestPersonas.LUKE_LOCAL_MEMBER.id, TestPersonas.DENVER_METRO_BRANCH_ID)

        then:
        HttpStatusException crossOrgEx = thrown()
        crossOrgEx.status == HttpStatus.FORBIDDEN

        when: "a region agent calls authorizeRegionUserView for another user"
        service.authorizeRegionUserView(TestPersonas.CONNIE_COUNTY_AGENT.id, TestPersonas.DANIEL_COUNTY_DIRECTOR.id, TestPersonas.DENVER_COUNTY_REGION_ID)

        then:
        HttpStatusException agentViewEx = thrown()
        agentViewEx.status == HttpStatus.FORBIDDEN
    }

    def "demoted standard user still in region_users receives FORBIDDEN on regional approval and region edits"() {
        given: "a region with a physical project inside it and a demoted STANDARD_USER still listed as region director"
        def polygon = denverBoxPolygon()
        def region = dataBuilder.region()
                .withName("Demoted User Region")
                .withGeometry(polygon)
                .persist()
        def org = dataBuilder.organization()
                .withName("Demoted User Org")
                .asPublic()
                .verified()
                .persist()
        def loc = dataBuilder.location()
                .withName("In Region Loc")
                .withAddress("123 Main", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("In Region Project")
                .withDescription("Description for demoted user test.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(loc)
                .persist()
        def demotedUser = saveUser(UserRole.STANDARD_USER)
        def demotedAgent = saveUser(UserRole.REGION_AGENT)
        def otherMember = saveUser(UserRole.STANDARD_USER)
        dataBuilder.regionUser().asRegionDirector(demotedUser, region).persist()
        dataBuilder.regionUser().asRegionDirector(demotedAgent, region).persist()
        dataBuilder.regionUser().asRegionAgent(otherMember, region).persist()
        def insidePoint = dataBuilder.geometryFactory.createPoint(new Coordinate(-104.9903, 39.7392))
        insidePoint.setSRID(4326)

        when: "the demoted user calls authorizeRegionalAdminApproval"
        service.authorizeRegionalAdminApproval(demotedUser.id(), project.id())

        then:
        HttpStatusException approvalEx = thrown()
        approvalEx.status == HttpStatus.FORBIDDEN

        when: "the demoted user calls authorizeLocationCreate"
        service.authorizeLocationCreate(demotedUser.id(), insidePoint)

        then:
        HttpStatusException locationEx = thrown()
        locationEx.status == HttpStatus.FORBIDDEN

        when: "the demoted user calls authorizeBoundaryCreate"
        service.authorizeBoundaryCreate(demotedUser.id(), polygon)

        then:
        HttpStatusException boundaryEx = thrown()
        boundaryEx.status == HttpStatus.FORBIDDEN

        when: "the demoted standard user calls authorizeRegionDirector"
        service.authorizeRegionDirector(demotedUser.id(), region.id())

        then:
        HttpStatusException dirEx = thrown()
        dirEx.status == HttpStatus.FORBIDDEN

        when: "the demoted standard user calls authorizeRegionUserView for another member"
        service.authorizeRegionUserView(demotedUser.id(), otherMember.id(), region.id())

        then:
        HttpStatusException viewEx = thrown()
        viewEx.status == HttpStatus.FORBIDDEN

        when: "a region agent with a stale REGION_DIRECTOR row calls authorizeRegionDirector"
        service.authorizeRegionDirector(demotedAgent.id(), region.id())

        then:
        HttpStatusException agentDirEx = thrown()
        agentDirEx.status == HttpStatus.FORBIDDEN

        when: "a region agent with a stale REGION_DIRECTOR row calls authorizeRegionUserView for another member"
        service.authorizeRegionUserView(demotedAgent.id(), otherMember.id(), region.id())

        then:
        HttpStatusException agentViewEx = thrown()
        agentViewEx.status == HttpStatus.FORBIDDEN
    }

    def "region agents face boundary rules when creating projects or assigning regions"() {
        given: "two regions, an organization, an agent assigned to region A, and a director assigned to region A"
        def regionA = dataBuilder.region().withName("Agent Region A").withGeometry(denverBoxPolygon()).persist()
        def regionB = dataBuilder.region().withName("Agent Region B").withGeometry(wyomingBoxPolygon()).persist()
        def org = dataBuilder.organization().withName("Agent Member Org").asPublic().verified().persist()
        dataBuilder.linkOrganizationToRegion(org, regionA)

        def agent = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(agent, regionA).persist()
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, regionA).persist()

        def memberAgent = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(memberAgent, regionA).persist()
        dataBuilder.organizationUser().asOrgMember(memberAgent, org).persist()

        def outsideLoc = dataBuilder.location()
                .withName("Wyoming Site")
                .withAddress("100 Pine St", "Laramie", "WY", "82070", "US")
                .atPoint(-106.2, 41.2)
                .build()

        def outsidePhysicalProject = dataBuilder.project()
                .forOrg(org)
                .withTitle("Out of Bounds Project")
                .withDescription("Physical project outside agent region.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(outsideLoc)
                .build()

        def virtualProjectInRegionA = dataBuilder.project()
                .forOrg(org)
                .inRegion(regionA)
                .withTitle("Virtual Region Project")
                .withDescription("Virtual project inside agent region.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        expect: "non-member agent can create a virtual project in their own region as PENDING, and out-of-region region assignment is denied"
        verifyAll {
            service.evaluateProjectCreationByUser(agent.id(), virtualProjectInRegionA) == ProjectStatus.PENDING
            service.canAssignManagingRegion(agent.id(), org.id(), regionB.id()) == false
            service.canAssignManagingRegion(director.id(), org.id(), regionB.id()) == false
        }

        when: "a region agent who is an org member submits a non-draft physical project outside their region"
        service.evaluateProjectCreationByUser(memberAgent.id(), outsidePhysicalProject)

        then:
        HttpStatusException boundaryEx = thrown()
        verifyAll(boundaryEx) {
            status == HttpStatus.FORBIDDEN
            message == "Region Agents cannot post outside their boundaries without Org Manager privileges"
        }
    }

    def "authorizeSystemAdmin allows global admins and rejects non-admin roles"() {
        given: "users with global admin, region director, and standard roles"
        def admin = saveUser(UserRole.GLOBAL_ADMIN)
        def director = saveUser(UserRole.REGION_DIRECTOR)
        def standardUser = saveUser(UserRole.STANDARD_USER)

        when: "a global admin calls authorizeSystemAdmin"
        service.authorizeSystemAdmin(admin.id())

        then:
        noExceptionThrown()

        when: "a standard user calls authorizeSystemAdmin"
        service.authorizeSystemAdmin(standardUser.id())

        then:
        HttpStatusException standardEx = thrown()
        standardEx.status == HttpStatus.FORBIDDEN

        when: "a region director calls authorizeSystemAdmin"
        service.authorizeSystemAdmin(director.id())

        then:
        HttpStatusException directorEx = thrown()
        directorEx.status == HttpStatus.FORBIDDEN
    }

    def "out-of-region admins cannot view non-active physical projects or virtual projects without a managing region"() {
        given: "two regions, an org, a physical pending project in region A, and a virtual pending project with no managing region"
        def regionA = dataBuilder.region().withName("View Check A").withGeometry(denverBoxPolygon()).persist()
        def regionB = dataBuilder.region().withName("View Check B").withGeometry(wyomingBoxPolygon()).persist()
        def org = dataBuilder.organization().withName("View Check Org").asPublic().verified().persist()
        dataBuilder.linkOrganizationToRegion(org, regionA)

        def locA = dataBuilder.location()
                .withName("Denver Site")
                .withAddress("123 Main St", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def physicalPending = dataBuilder.project()
                .forOrg(org)
                .withTitle("Physical Pending Project")
                .withDescription("Pending physical project.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(locA)
                .persist()
        def virtualWithoutRegion = dataBuilder.project()
                .forOrg(org)
                .withTitle("Virtual No Region Project")
                .withDescription("Pending virtual project without managing region.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        def agentB = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(agentB, regionB).persist()
        def directorB = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(directorB, regionB).persist()

        expect:
        verifyAll {
            service.canViewProject(agentB.id(), physicalPending) == false
            service.canViewProject(directorB.id(), physicalPending) == false
            service.canViewProject(agentB.id(), virtualWithoutRegion) == false
            service.canViewProject(directorB.id(), virtualWithoutRegion) == false
        }
    }

    def "public orgs with future verification expiry are visible, and shift by user ID and missing-location checks enforce access"() {
        given: "an org, a region, a director, an org manager, a sponsor, an outsider, and a project"
        def region = dataBuilder.region().withName("Shift Access Region").withGeometry(denverBoxPolygon()).persist()
        def org = dataBuilder.organization().withName("Shift Access Org").asPublic().verified().persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Shift Access Project")
                .withDescription("Project for shift access checks.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .persist()

        def futureVerifiedOrg = dataBuilder.organization()
                .withName("Future Verified Public Org")
                .asPublic()
                .verified()
                .withVerificationExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30))
                .build()

        def manager = saveUser(UserRole.STANDARD_USER)
        dataBuilder.organizationUser().asOrgManager(manager, org).persist()
        def sponsor = saveUser(UserRole.STANDARD_USER)
        addProjectSponsor(sponsor.id(), project.id())
        def outsider = saveUser(UserRole.STANDARD_USER)
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()

        def start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3)
        def orphanShift = new Shift(null, null, true, null, start, start.plusHours(2), [])

        expect: "public org with future expiration, shift management by user ID, and anonymous orphan shift view behave properly"
        verifyAll {
            service.canViewOrganization(null, futureVerifiedOrg) == true
            service.canManageShift(manager.id(), project) == true
            service.canManageShift(sponsor.id(), project) == true
            service.canManageShift(outsider.id(), project) == false
            service.canViewShift(null, orphanShift) == false
        }

        when: "authorized users call authorizeShiftManagement by user ID"
        service.authorizeShiftManagement(manager.id(), project)
        service.authorizeShiftManagement(sponsor.id(), project)

        then:
        noExceptionThrown()

        when: "outsider calls authorizeShiftManagement by user ID"
        service.authorizeShiftManagement(outsider.id(), project)

        then:
        HttpStatusException shiftForbidEx = thrown()
        shiftForbidEx.status == HttpStatus.FORBIDDEN

        when: "missing user calls authorizeShiftManagement by user ID"
        service.authorizeShiftManagement(UUID.randomUUID(), project)

        then:
        HttpStatusException shiftMissingEx = thrown()
        shiftMissingEx.status == HttpStatus.UNAUTHORIZED

        when: "anonymous user calls authorizeShiftView on an orphan shift"
        service.authorizeShiftView(null, orphanShift)

        then:
        HttpStatusException orphanViewEx = thrown()
        orphanViewEx.status == HttpStatus.UNAUTHORIZED

        when: "a region director calls authorizeLocationCreate with a null point"
        service.authorizeLocationCreate(director.id(), null)

        then:
        HttpStatusException nullPointEx = thrown()
        nullPointEx.status == HttpStatus.FORBIDDEN
    }

    def "null and unsaved records are handled safely across project, shift, location, and organization checks"() {
        given: "users and regions for unsaved and partial record checks"
        def region = dataBuilder.region().withName("Saved Guard Region").withGeometry(denverBoxPolygon()).persist()
        def admin = saveUser(UserRole.GLOBAL_ADMIN)
        def standardUser = saveUser(UserRole.STANDARD_USER)
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()
        def agent = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(agent, region).persist()

        def unsavedOrg = new Organization(null, "Unsaved Org", "https://example.org", null, false, VerificationStatus.UNVERIFIED, null, [])
        def unsavedRegion = new AdministrativeRegion(null, "Unsaved Region", null, denverBoxPolygon())
        def locationWithoutGeom = new Location(null, "No Geom", "123 St", "Denver", "CO", "80202", "US", null, "America/Denver")

        def projectWithNullGeomLoc = dataBuilder.project()
                .withoutOrg()
                .withTitle("Null Geom Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(locationWithoutGeom)
                .build()

        def projectWithoutOrg = dataBuilder.project()
                .withoutOrg()
                .withTitle("No Org Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        def projectWithNullLocations = dataBuilder.project()
                .withoutOrg()
                .withTitle("Null Locations Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .withNullLocations()
                .build()

        def projectWithUnsavedRegion = dataBuilder.project()
                .withoutOrg()
                .inRegion(unsavedRegion)
                .withTitle("Unsaved Region Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        def projectWithUnsavedOrg = dataBuilder.project()
                .forOrg(unsavedOrg)
                .withTitle("Unsaved Org Project")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        def virtualWithUnsavedRegion = dataBuilder.project()
                .withId(UUID.randomUUID())
                .withoutOrg()
                .inRegion(unsavedRegion)
                .withTitle("Virtual Project Missing Region ID")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .build()

        expect: "all unsaved and null guards safely return false or PENDING as appropriate"
        verifyAll {
            service.areAllLocationsInOrgRegion(projectWithNullGeomLoc, orgId) == false
            service.evaluateProjectCreationByUser(admin, projectWithoutOrg) == ProjectStatus.PENDING
            service.evaluateProjectCreationByUser(admin, projectWithNullLocations) == ProjectStatus.PENDING
            service.canModifyProject(director, projectWithoutOrg) == false
            service.canModifyProject(standardUser, projectWithUnsavedOrg) == false
            service.canViewProject(standardUser.id(), projectWithoutOrg) == false
            service.canViewProject(standardUser.id(), projectWithUnsavedOrg) == false
            service.canViewProject(agent.id(), projectWithoutOrg) == false
            service.canViewProject(agent.id(), virtualWithUnsavedRegion) == false
            service.canManageShift(standardUser, projectWithoutOrg) == false
            service.canManageShift(standardUser, projectWithUnsavedOrg) == false
            service.canViewOrganization(standardUser.id(), unsavedOrg) == false
        }

        when: "a standard user attempts to create a project with no organization"
        service.authorizeProjectCreation(standardUser, projectWithoutOrg)

        then:
        HttpStatusException noOrgEx = thrown()
        noOrgEx.status == HttpStatus.FORBIDDEN

        when: "a region agent attempts to create a virtual project whose managing region has no ID"
        service.authorizeProjectCreation(agent, projectWithUnsavedRegion)

        then:
        HttpStatusException unsavedRegionEx = thrown()
        unsavedRegionEx.status == HttpStatus.FORBIDDEN
    }

    def "verified org managers and agents in assigned regions get active status while global admins handle admin guards"() {
        given: "a region, verified and unverified orgs, and locations inside the region"
        def polygon = denverBoxPolygon()
        def region = dataBuilder.region().withName("Direct Active Region").withGeometry(polygon).persist()
        def verifiedOrg = dataBuilder.organization().withName("Direct Active Org").asPublic().verified().persist()
        dataBuilder.linkOrganizationToRegion(verifiedOrg, region)
        def unverifiedOrg = dataBuilder.organization().withName("Unverified Public Org").asPublic().unverified().persist()
        dataBuilder.linkOrganizationToRegion(unverifiedOrg, region)
        def privateVerifiedOrg = dataBuilder.organization().withName("Private Verified Org").asPrivate().verified().persist()

        def loc = dataBuilder.location()
                .withName("Denver Active Site")
                .withAddress("123 Main St", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def boundary = dataBuilder.boundary().withName("Direct Active Boundary").withGeometry(polygon).persist()

        def admin = saveUser(UserRole.GLOBAL_ADMIN)
        def orgManager = saveUser(UserRole.STANDARD_USER)
        dataBuilder.organizationUser().asOrgManager(orgManager, verifiedOrg).persist()
        def verifiedMember = saveUser(UserRole.STANDARD_USER)
        dataBuilder.organizationUser().asOrgMember(verifiedMember, verifiedOrg).persist()
        def unverifiedOrgManager = saveUser(UserRole.STANDARD_USER)
        dataBuilder.organizationUser().asOrgManager(unverifiedOrgManager, unverifiedOrg).persist()
        def agent = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(agent, region).persist()
        def standardUser = saveUser(UserRole.STANDARD_USER)

        def outsideLoc = dataBuilder.location()
                .withName("Wyoming Out Site")
                .withAddress("456 Far St", "Laramie", "WY", "82070", "US")
                .atPoint(-106.2, 41.2)
                .persist()

        def activeByManagerProject = dataBuilder.project()
                .forOrg(verifiedOrg)
                .withTitle("Manager Active Project")
                .withDescription("Physical project submitted by verified org manager.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(loc)
                .build()

        def pendingOutsideByManagerProject = dataBuilder.project()
                .forOrg(verifiedOrg)
                .withTitle("Manager Outside Project")
                .withDescription("Physical project outside org region.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(outsideLoc)
                .build()

        def activeByAgentProject = dataBuilder.project()
                .forOrg(unverifiedOrg)
                .withTitle("Agent Active Project")
                .withDescription("Physical project submitted by region agent in assigned region.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(loc)
                .build()

        def pendingByUnverifiedManagerProject = dataBuilder.project()
                .forOrg(unverifiedOrg)
                .withTitle("Unverified Org Manager Project")
                .withDescription("Physical project by unverified org manager.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(loc)
                .build()

        def insidePoint = dataBuilder.geometryFactory.createPoint(new Coordinate(-104.9903, 39.7392))
        insidePoint.setSRID(4326)

        expect: "creation status, shift management, and org visibility evaluate each role branch"
        verifyAll {
            service.evaluateProjectCreationByUser(orgManager.id(), activeByManagerProject) == ProjectStatus.ACTIVE
            service.evaluateProjectCreationByUser(verifiedMember.id(), activeByManagerProject) == ProjectStatus.PENDING
            service.evaluateProjectCreationByUser(orgManager.id(), pendingOutsideByManagerProject) == ProjectStatus.PENDING
            service.evaluateProjectCreationByUser(agent.id(), activeByAgentProject) == ProjectStatus.ACTIVE
            service.evaluateProjectCreationByUser(unverifiedOrgManager.id(), pendingByUnverifiedManagerProject) == ProjectStatus.PENDING
            service.canManageShift(admin.id(), activeByManagerProject) == true
            service.canViewOrganization(null, unverifiedOrg) == false
            service.canViewOrganization(null, privateVerifiedOrg) == false
        }

        when: "global admin invokes admin guards for org, location, boundary, and users"
        service.authorizeOrganizationAdmin(admin.id(), verifiedOrg.id())
        service.authorizeLocationCreate(admin.id(), insidePoint)
        service.authorizeLocationUpdate(admin.id(), loc.id(), insidePoint)
        service.authorizeBoundaryCreate(admin.id(), polygon)
        service.authorizeBoundaryUpdate(admin.id(), boundary.id(), polygon)
        service.authorizeUserManager(admin.id())

        then:
        noExceptionThrown()

        when: "standard user invokes location, boundary, and user manager guards"
        service.authorizeLocationCreate(standardUser.id(), insidePoint)

        then:
        HttpStatusException locForbiddenEx = thrown()
        locForbiddenEx.status == HttpStatus.FORBIDDEN

        when: "standard user invokes boundary create guard"
        service.authorizeBoundaryCreate(standardUser.id(), polygon)

        then:
        HttpStatusException bndForbiddenEx = thrown()
        bndForbiddenEx.status == HttpStatus.FORBIDDEN

        when: "standard user invokes user manager guard"
        service.authorizeUserManager(standardUser.id())

        then:
        HttpStatusException userMgrForbiddenEx = thrown()
        userMgrForbiddenEx.status == HttpStatus.FORBIDDEN
    }
}
