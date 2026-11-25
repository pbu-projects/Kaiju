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
import spock.lang.Unroll

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

    @Unroll
    def "areAllLocationsInOrgRegion returns false when project locations are #locationState"() {
        given:
        def builder = dataBuilder.project()
                .withId(projectId)
                .withTitle("Title")
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
        def project = (useNullLocations ? builder.withNullLocations() : builder).build()

        expect:
        service.areAllLocationsInOrgRegion(project, orgId) == false

        where:
        locationState | useNullLocations
        "empty"       | false
        "null"        | true
    }

    @Unroll
    def "areAllLocationsInOrgRegion returns #expected when project location is #siteLabel"() {
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

        and: "a project at the target location"
        def location = dataBuilder.location()
                .withName(siteLabel)
                .withAddress("123 St", city, "CO", zip, "US")
                .atPoint(lon, lat)
                .build()
        def project = dataBuilder.project()
                .withTitle(siteLabel)
                .withDescription("Desc")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.ACTIVE)
                .atLocation(location)
                .build()

        expect:
        service.areAllLocationsInOrgRegion(project, org.id()) == expected

        where:
        siteLabel        | city      | zip     | lon        | lat     | expected
        "inside region"  | "Denver"  | "80202" | -104.9903d | 39.7392d | true
        "outside region" | "Boulder" | "80302" | -105.2705d | 40.0150d | false
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

    private Map persistDirectorPhysicalProjectFixture() {
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
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()
        return [director: director, project: project]
    }

    def "hasJurisdictionOverAllProjectLocations returns true for a region director over a physical project in their region"() {
        given: "a region director and a physical project inside their region"
        def fixtures = persistDirectorPhysicalProjectFixture()

        expect:
        queryRepository.hasJurisdictionOverAllProjectLocations((fixtures.director as User).id(), fixtures.project.id()) == true
    }

    def "canReassignProject returns true for a region director over a physical project in their region"() {
        given: "a region director and a physical project inside their region"
        def fixtures = persistDirectorPhysicalProjectFixture()

        expect:
        service.canReassignProject((fixtures.director as User).id(), fixtures.project) == true
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

    @Unroll
    def "areAllLocationsInOrgRegion returns #expected when checking multiple locations where #caseLabel"() {
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

        and: "multiple locations on the project"
        def locations = coords.collect { pair ->
            dataBuilder.location()
                    .withName("Loc ${pair[0]}")
                    .withAddress("Addr", "City", null, null, "US")
                    .atPoint(pair[0] as double, pair[1] as double)
                    .build()
        }
        def project = dataBuilder.project()
                .withTitle("Batch Project")
                .withDescription("D")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocations(locations)
                .build()

        expect:
        service.areAllLocationsInOrgRegion(project, org.id()) == expected

        where:
        caseLabel              | coords                                                 | expected
        "all sites are inside" | [[-105.0d, 39.7d], [-104.9d, 39.7d], [-104.8d, 39.7d]] | true
        "one site is outside"  | [[-105.0d, 39.7d], [-106.0d, 41.0d]]                   | false
    }

    private Map persistAssignManagingRegionFixtures() {
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

        def admin = saveUser(UserRole.GLOBAL_ADMIN)
        def standardUser = saveUser(UserRole.STANDARD_USER)
        def orgManager = saveUser(UserRole.STANDARD_USER)
        dataBuilder.organizationUser().asOrgManager(orgManager, org).persist()
        def regionalAgent = saveUser(UserRole.REGION_AGENT)
        dataBuilder.regionUser().asRegionAgent(regionalAgent, region).persist()

        return [
                region       : region,
                org          : org,
                admin        : admin,
                standardUser : standardUser,
                orgManager   : orgManager,
                regionalAgent: regionalAgent
        ]
    }

    @Unroll
    def "canAssignManagingRegion returns #expected for #actorLabel"() {
        given: "a region, organization, and actor"
        def fixtures = persistAssignManagingRegionFixtures()
        def actor = fixtures[actorKey] as User

        expect:
        service.canAssignManagingRegion(actor.id(), fixtures.org.id(), fixtures.region.id()) == expected

        where:
        actorLabel       | actorKey        | expected
        "global admin"   | "admin"         | true
        "org manager"    | "orgManager"    | true
        "regional agent" | "regionalAgent" | true
        "standard user"  | "standardUser"  | false
    }

    def "canAssignManagingRegion throws UNAUTHORIZED for a missing actor"() {
        given: "a region and organization"
        def fixtures = persistAssignManagingRegionFixtures()

        when:
        service.canAssignManagingRegion(UUID.randomUUID(), fixtures.org.id(), fixtures.region.id())

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

    private Map persistProjectAndShiftViewFixtures() {
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

        return [
                activeProject : activeProject,
                pendingProject: pendingProject,
                admin         : admin,
                outsider      : outsider,
                orgMember     : orgMember,
                sponsor       : sponsor,
                regionalAgent : regionalAgent,
                activeShift   : activeShift,
                pendingShift  : pendingShift,
                orphanShift   : orphanShift
        ]
    }

    @Unroll
    def "canViewProject returns #expected when #viewerLabel views a #projectLabel project"() {
        given: "projects and users"
        def fixtures = persistProjectAndShiftViewFixtures()
        def viewerId = viewerKey ? (fixtures[viewerKey] as User).id() : null
        def project = fixtures[projectKey]

        expect:
        service.canViewProject(viewerId, project) == expected

        where:
        viewerLabel      | viewerKey       | projectLabel | projectKey       | expected
        "anonymous user" | null            | "active"     | "activeProject"  | true
        "anonymous user" | null            | "pending"    | "pendingProject" | false
        "outsider"       | "outsider"      | "pending"    | "pendingProject" | false
        "global admin"   | "admin"         | "pending"    | "pendingProject" | true
        "org member"     | "orgMember"     | "pending"    | "pendingProject" | true
        "sponsor"        | "sponsor"       | "pending"    | "pendingProject" | true
        "regional agent" | "regionalAgent" | "pending"    | "pendingProject" | true
    }

    @Unroll
    def "canViewProject throws UNAUTHORIZED for a missing actor on a #projectLabel project"() {
        given: "projects"
        def fixtures = persistProjectAndShiftViewFixtures()

        when:
        service.canViewProject(UUID.randomUUID(), fixtures[projectKey])

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED

        where:
        projectLabel | projectKey
        "active"     | "activeProject"
        "pending"    | "pendingProject"
    }

    @Unroll
    def "canViewShift returns #expected when #viewerLabel views a #shiftLabel shift"() {
        given: "shifts and users"
        def fixtures = persistProjectAndShiftViewFixtures()
        def viewerId = viewerKey ? (fixtures[viewerKey] as User).id() : null
        def shift = fixtures[shiftKey] as Shift

        expect:
        service.canViewShift(viewerId, shift) == expected

        where:
        viewerLabel      | viewerKey  | shiftLabel | shiftKey       | expected
        "anonymous user" | null       | "active"   | "activeShift"  | true
        "outsider"       | "outsider" | "pending"  | "pendingShift" | false
        "sponsor"        | "sponsor"  | "pending"  | "pendingShift" | true
        "global admin"   | "admin"    | "orphan"   | "orphanShift"  | false
    }

    def "canViewShift throws UNAUTHORIZED for a missing actor on an active shift"() {
        given: "an active shift"
        def fixtures = persistProjectAndShiftViewFixtures()

        when:
        service.canViewShift(UUID.randomUUID(), fixtures.activeShift as Shift)

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    def "authorizeShiftView allows a project sponsor to view a pending shift"() {
        given: "a pending shift and its sponsor"
        def fixtures = persistProjectAndShiftViewFixtures()

        when:
        service.authorizeShiftView((fixtures.sponsor as User).id(), fixtures.pendingShift as Shift)

        then:
        noExceptionThrown()
    }

    @Unroll
    def "authorizeShiftView throws #expectedStatus when #callerLabel views a #shiftLabel shift"() {
        given: "shifts and users"
        def fixtures = persistProjectAndShiftViewFixtures()
        UUID callerId = callerMode == "missing" ? UUID.randomUUID() : (callerMode == "anonymous" ? null : (fixtures[callerMode] as User).id())
        def shift = fixtures[shiftKey] as Shift

        when:
        service.authorizeShiftView(callerId, shift)

        then:
        HttpStatusException ex = thrown()
        ex.status == expectedStatus

        where:
        callerLabel      | callerMode  | shiftLabel | shiftKey       | expectedStatus
        "outsider"       | "outsider"  | "pending"  | "pendingShift" | HttpStatus.FORBIDDEN
        "anonymous user" | "anonymous" | "pending"  | "pendingShift" | HttpStatus.UNAUTHORIZED
        "missing actor"  | "missing"   | "active"   | "activeShift"  | HttpStatus.UNAUTHORIZED
    }

    private Map persistShiftServiceFixtures() {
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

        return [
                project         : project,
                locA            : locA,
                inRegionAgent   : inRegionAgent,
                outOfRegionAgent: outOfRegionAgent,
                sponsor         : sponsor,
                outsider        : outsider,
                start           : start,
                end             : end,
                candidateShift  : candidateShift
        ]
    }

    def "DefaultShiftService allows an in-region agent to create a shift"() {
        given: "an active project and an in-region agent"
        def fixtures = persistShiftServiceFixtures()

        when:
        def created = track(shiftService.createShift(fixtures.candidateShift as Shift, (fixtures.inRegionAgent as User).id()))

        then:
        created.id() != null
    }

    def "DefaultShiftService allows a project sponsor to update a shift"() {
        given: "an existing shift and a project sponsor"
        def fixtures = persistShiftServiceFixtures()
        def created = track(shiftService.createShift(fixtures.candidateShift as Shift, (fixtures.inRegionAgent as User).id()))
        def start = fixtures.start as OffsetDateTime
        def end = fixtures.end as OffsetDateTime

        when:
        def updated = shiftService.updateShift(
                created.id(),
                new Shift(created.id(), fixtures.project, false, fixtures.locA as Location, start.plusHours(1), end.plusHours(1), []),
                (fixtures.sponsor as User).id()
        )

        then:
        updated.startTime() == start.plusHours(1)
    }

    def "DefaultShiftService denies shift creation by an out-of-region agent"() {
        given: "an active project and an out-of-region agent"
        def fixtures = persistShiftServiceFixtures()

        when:
        shiftService.createShift(fixtures.candidateShift as Shift, (fixtures.outOfRegionAgent as User).id())

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN
    }

    def "DefaultShiftService denies shift deletion by an outsider"() {
        given: "an existing shift and an outsider"
        def fixtures = persistShiftServiceFixtures()
        def created = track(shiftService.createShift(fixtures.candidateShift as Shift, (fixtures.inRegionAgent as User).id()))

        when:
        shiftService.deleteShift(created.id(), (fixtures.outsider as User).id())

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN
    }

    def "DefaultShiftService allows a project sponsor to delete a shift"() {
        given: "an existing shift and a project sponsor"
        def fixtures = persistShiftServiceFixtures()
        def created = track(shiftService.createShift(fixtures.candidateShift as Shift, (fixtures.inRegionAgent as User).id()))

        when:
        shiftService.deleteShift(created.id(), (fixtures.sponsor as User).id())

        then:
        noExceptionThrown()
    }

    @Unroll
    def "DefaultShiftService throws UNAUTHORIZED on #operation for a missing actor"() {
        given: "a candidate shift"
        def fixtures = persistShiftServiceFixtures()
        UUID missingActorId = UUID.randomUUID()

        when:
        switch (operation) {
            case "createShift":
                shiftService.createShift(fixtures.candidateShift as Shift, missingActorId)
                break
            case "updateShift":
                shiftService.updateShift(UUID.randomUUID(), fixtures.candidateShift as Shift, missingActorId)
                break
            case "deleteShift":
                shiftService.deleteShift(UUID.randomUUID(), missingActorId)
                break
        }

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED

        where:
        operation << ["createShift", "updateShift", "deleteShift"]
    }

    def "DefaultUserService getCurrentUser returns current user for self"() {
        given: "a standard user"
        def alice = saveUser(UserRole.STANDARD_USER)

        expect: "self lookup returns the user"
        userService.getCurrentUser(alice.id()).id() == alice.id()
    }

    @Unroll
    def "DefaultUserService getUserById allows #actorRole to read target user"() {
        given: "a target user and an actor"
        def alice = saveUser(UserRole.STANDARD_USER)
        def actorId = actorRole == "self" ? alice.id() : saveUser(UserRole.GLOBAL_ADMIN).id()

        expect: "target user is returned"
        userService.getUserById(alice.id(), actorId).isPresent()

        where:
        actorRole << ["self", "GLOBAL_ADMIN"]
    }

    def "DefaultUserService getUserById throws FORBIDDEN when another standard user reads user"() {
        given: "two standard users"
        def alice = saveUser(UserRole.STANDARD_USER)
        def bob = saveUser(UserRole.STANDARD_USER)

        when: "bob tries to read alice"
        userService.getUserById(alice.id(), bob.id())

        then: "FORBIDDEN is thrown"
        HttpStatusException readEx = thrown()
        readEx.status == HttpStatus.FORBIDDEN
    }

    def "DefaultUserService createUser throws FORBIDDEN for standard user"() {
        given: "a standard user"
        def bob = saveUser(UserRole.STANDARD_USER)

        when: "bob tries to create a user"
        userService.createUser(new User(null, "new@example.com", UserRole.STANDARD_USER, OffsetDateTime.now(ZoneOffset.UTC)), bob.id())

        then: "FORBIDDEN is thrown"
        HttpStatusException createEx = thrown()
        createEx.status == HttpStatus.FORBIDDEN
    }

    def "DefaultUserService updateUser throws FORBIDDEN for standard user"() {
        given: "two standard users"
        def alice = saveUser(UserRole.STANDARD_USER)
        def bob = saveUser(UserRole.STANDARD_USER)

        when: "bob tries to update alice"
        userService.updateUser(alice.id(), new User(alice.id(), "hacked@example.com", null, null), bob.id())

        then: "FORBIDDEN is thrown"
        HttpStatusException updateEx = thrown()
        updateEx.status == HttpStatus.FORBIDDEN
    }

    def "DefaultUserService deleteUser throws FORBIDDEN for standard user"() {
        given: "two standard users"
        def alice = saveUser(UserRole.STANDARD_USER)
        def bob = saveUser(UserRole.STANDARD_USER)

        when: "bob tries to delete alice"
        userService.deleteUser(alice.id(), bob.id())

        then: "FORBIDDEN is thrown"
        HttpStatusException deleteEx = thrown()
        deleteEx.status == HttpStatus.FORBIDDEN
    }

    private Map persistOrgRegionAuthFixtures() {
        def polygon = denverBoxPolygon()
        def region = dataBuilder.region().withName("Auth Region").withGeometry(polygon).persist()
        def otherRegion = dataBuilder.region().withName("Other Auth Region").withGeometry(wyomingBoxPolygon()).persist()
        def privateOrg = dataBuilder.organization().withName("Private Org").asPrivate().unverified().persist()
        def publicOrg = dataBuilder.organization().withName("Public Org").asPublic().verified().persist()

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

        return [
                region       : region,
                privateOrg   : privateOrg,
                publicOrg    : publicOrg,
                admin        : admin,
                outsider     : outsider,
                orgManager   : orgManager,
                orgAdmin     : orgAdmin,
                director     : director,
                agent        : agent,
                otherDirector: otherDirector
        ]
    }

    @Unroll
    def "canViewOrganization returns #expected when #callerLabel views a #orgLabel"() {
        given: "organizations and users"
        def fixtures = persistOrgRegionAuthFixtures()
        def callerId = callerKey ? (fixtures[callerKey] as User).id() : null
        def org = fixtures[orgKey] as Organization

        expect:
        service.canViewOrganization(callerId, org) == expected

        where:
        callerLabel      | callerKey    | orgLabel               | orgKey       | expected
        "anonymous user" | null         | "public organization"  | "publicOrg"  | true
        "anonymous user" | null         | "private organization" | "privateOrg" | false
        "outsider"       | "outsider"   | "private organization" | "privateOrg" | false
        "org manager"    | "orgManager" | "private organization" | "privateOrg" | true
        "global admin"   | "admin"      | "private organization" | "privateOrg" | true
    }

    def "canViewOrganization throws UNAUTHORIZED when a missing actor views a public organization"() {
        given: "a public organization"
        def fixtures = persistOrgRegionAuthFixtures()

        when:
        service.canViewOrganization(UUID.randomUUID(), fixtures.publicOrg as Organization)

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    @Unroll
    def "authorizeOrganizationView allows #callerLabel to view a #orgLabel"() {
        given: "organizations and users"
        def fixtures = persistOrgRegionAuthFixtures()
        def callerId = callerKey ? (fixtures[callerKey] as User).id() : null
        def org = fixtures[orgKey] as Organization

        when:
        service.authorizeOrganizationView(callerId, org)

        then:
        noExceptionThrown()

        where:
        callerLabel      | callerKey    | orgLabel               | orgKey
        "anonymous user" | null         | "public organization"  | "publicOrg"
        "org manager"    | "orgManager" | "private organization" | "privateOrg"
        "global admin"   | "admin"      | "private organization" | "privateOrg"
    }

    @Unroll
    def "authorizeOrganizationView throws #expectedStatus when #callerLabel views a #orgLabel"() {
        given: "organizations and users"
        def fixtures = persistOrgRegionAuthFixtures()
        UUID callerId = callerMode == "missing" ? UUID.randomUUID() : (callerMode == "anonymous" ? null : (fixtures[callerMode] as User).id())
        def org = fixtures[orgKey] as Organization

        when:
        service.authorizeOrganizationView(callerId, org)

        then:
        HttpStatusException ex = thrown()
        ex.status == expectedStatus

        where:
        callerLabel      | callerMode  | orgLabel               | orgKey       | expectedStatus
        "outsider"       | "outsider"  | "private organization" | "privateOrg" | HttpStatus.FORBIDDEN
        "anonymous user" | "anonymous" | "private organization" | "privateOrg" | HttpStatus.UNAUTHORIZED
        "missing actor"  | "missing"   | "public organization"  | "publicOrg"  | HttpStatus.UNAUTHORIZED
    }

    @Unroll
    def "authorizeOrganizationUpdate allows #callerLabel"() {
        given: "a private organization and authorized caller"
        def fixtures = persistOrgRegionAuthFixtures()
        def callerId = (fixtures[callerKey] as User).id()

        when:
        service.authorizeOrganizationUpdate(callerId, (fixtures.privateOrg as Organization).id())

        then:
        noExceptionThrown()

        where:
        callerLabel    | callerKey
        "org manager"  | "orgManager"
        "global admin" | "admin"
    }

    def "authorizeOrganizationUpdate throws FORBIDDEN for an outsider"() {
        given: "a private organization and an outsider"
        def fixtures = persistOrgRegionAuthFixtures()

        when:
        service.authorizeOrganizationUpdate((fixtures.outsider as User).id(), (fixtures.privateOrg as Organization).id())

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN
    }

    def "authorizeOrganizationAdmin allows an org admin"() {
        given: "a private organization and an org admin"
        def fixtures = persistOrgRegionAuthFixtures()

        when:
        service.authorizeOrganizationAdmin((fixtures.orgAdmin as User).id(), (fixtures.privateOrg as Organization).id())

        then:
        noExceptionThrown()
    }

    def "authorizeOrganizationAdmin throws FORBIDDEN for an org manager"() {
        given: "a private organization and an org manager"
        def fixtures = persistOrgRegionAuthFixtures()

        when:
        service.authorizeOrganizationAdmin((fixtures.orgManager as User).id(), (fixtures.privateOrg as Organization).id())

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN
    }

    def "authorizeOrganizationVerification returns the global admin actor"() {
        given: "a global admin"
        def fixtures = persistOrgRegionAuthFixtures()
        def admin = fixtures.admin as User

        when:
        User verifiedActor = service.authorizeOrganizationVerification(admin.id())

        then:
        noExceptionThrown()
        verifiedActor.id() == admin.id()
    }

    def "authorizeOrganizationVerification throws FORBIDDEN for an outsider"() {
        given: "an outsider"
        def fixtures = persistOrgRegionAuthFixtures()

        when:
        service.authorizeOrganizationVerification((fixtures.outsider as User).id())

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN
    }

    @Unroll
    def "authorizeRegionDirector allows #callerLabel on a region"() {
        given: "a region and authorized caller"
        def fixtures = persistOrgRegionAuthFixtures()

        when:
        service.authorizeRegionDirector((fixtures[callerKey] as User).id(), (fixtures.region as AdministrativeRegion).id())

        then:
        noExceptionThrown()

        where:
        callerLabel       | callerKey
        "region director" | "director"
        "global admin"    | "admin"
    }

    @Unroll
    def "authorizeRegionDirector throws FORBIDDEN for #callerLabel"() {
        given: "a region and unauthorized caller"
        def fixtures = persistOrgRegionAuthFixtures()

        when:
        service.authorizeRegionDirector((fixtures[callerKey] as User).id(), (fixtures.region as AdministrativeRegion).id())

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN

        where:
        callerLabel             | callerKey
        "region agent"          | "agent"
        "cross-region director" | "otherDirector"
    }

    private Map persistLocationBoundaryAuthFixtures() {
        def inPolygon = denverBoxPolygon()
        def outPolygon = wyomingBoxPolygon()

        def region = dataBuilder.region().withName("Gap Region").withGeometry(inPolygon).persist()
        def director = saveUser(UserRole.REGION_DIRECTOR)
        dataBuilder.regionUser().asRegionDirector(director, region).persist()
        def admin = saveUser(UserRole.GLOBAL_ADMIN)

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

        def insidePoint = dataBuilder.geometryFactory.createPoint(new Coordinate(-104.9903, 39.7392))
        insidePoint.setSRID(4326)
        def outsidePoint = dataBuilder.geometryFactory.createPoint(new Coordinate(-106.2, 41.2))
        outsidePoint.setSRID(4326)

        return [
                inPolygon   : inPolygon,
                outPolygon  : outPolygon,
                director    : director,
                admin       : admin,
                inLoc       : inLoc,
                outLoc      : outLoc,
                inBoundary  : inBoundary,
                outBoundary : outBoundary,
                insidePoint : insidePoint,
                outsidePoint: outsidePoint
        ]
    }

    @Unroll
    def "location authorization succeeds when #actionLabel"() {
        given: "regions, locations, and authorized callers"
        def fixtures = persistLocationBoundaryAuthFixtures()
        def directorId = (fixtures.director as User).id()
        def adminId = (fixtures.admin as User).id()
        def inLocId = (fixtures.inLoc as Location).id()

        when:
        switch (actionCase) {
            case "create":
                service.authorizeLocationCreate(directorId, fixtures.insidePoint)
                break
            case "update":
                service.authorizeLocationUpdate(directorId, inLocId, fixtures.insidePoint)
                break
            case "delete":
                service.authorizeLocationDelete(directorId, inLocId)
                break
            case "adminDeleteMissing":
                service.authorizeLocationDelete(adminId, UUID.randomUUID())
                break
        }

        then:
        noExceptionThrown()

        where:
        actionLabel                                | actionCase
        "director creates location inside region"  | "create"
        "director updates location inside region"  | "update"
        "director deletes location inside region"  | "delete"
        "global admin deletes missing location"    | "adminDeleteMissing"
    }

    @Unroll
    def "location authorization throws FORBIDDEN when #actionLabel"() {
        given: "a region director and locations inside and outside the region"
        def fixtures = persistLocationBoundaryAuthFixtures()
        def directorId = (fixtures.director as User).id()
        def inLocId = (fixtures.inLoc as Location).id()
        def outLocId = (fixtures.outLoc as Location).id()

        when:
        switch (actionCase) {
            case "createOutside":
                service.authorizeLocationCreate(directorId, fixtures.outsidePoint)
                break
            case "updateOutside":
                service.authorizeLocationUpdate(directorId, outLocId, fixtures.outsidePoint)
                break
            case "moveOutside":
                service.authorizeLocationUpdate(directorId, inLocId, fixtures.outsidePoint)
                break
            case "updateNull":
                service.authorizeLocationUpdate(directorId, inLocId, null)
                break
            case "deleteOutside":
                service.authorizeLocationDelete(directorId, outLocId)
                break
            case "deleteMissing":
                service.authorizeLocationDelete(directorId, UUID.randomUUID())
                break
        }

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN

        where:
        actionLabel                                  | actionCase
        "director creates location outside region"   | "createOutside"
        "director updates out-of-region location"    | "updateOutside"
        "director moves in-region location outside"  | "moveOutside"
        "director updates location with null point"  | "updateNull"
        "director deletes out-of-region location"    | "deleteOutside"
        "director deletes missing location"          | "deleteMissing"
    }

    @Unroll
    def "boundary authorization succeeds when #actionLabel"() {
        given: "regions, boundaries, and authorized callers"
        def fixtures = persistLocationBoundaryAuthFixtures()
        def directorId = (fixtures.director as User).id()
        def adminId = (fixtures.admin as User).id()
        def inBoundaryId = fixtures.inBoundary.id()
        def inPolygon = fixtures.inPolygon as Polygon

        when:
        switch (actionCase) {
            case "create":
                service.authorizeBoundaryCreate(directorId, inPolygon)
                break
            case "update":
                service.authorizeBoundaryUpdate(directorId, inBoundaryId, inPolygon)
                break
            case "delete":
                service.authorizeBoundaryDelete(directorId, inBoundaryId)
                break
            case "adminDeleteMissing":
                service.authorizeBoundaryDelete(adminId, UUID.randomUUID())
                break
        }

        then:
        noExceptionThrown()

        where:
        actionLabel                                | actionCase
        "director creates boundary inside region"  | "create"
        "director updates boundary inside region"  | "update"
        "director deletes boundary inside region"  | "delete"
        "global admin deletes missing boundary"    | "adminDeleteMissing"
    }

    @Unroll
    def "boundary authorization throws FORBIDDEN when #actionLabel"() {
        given: "a region director and boundaries inside and outside the region"
        def fixtures = persistLocationBoundaryAuthFixtures()
        def directorId = (fixtures.director as User).id()
        def inBoundaryId = fixtures.inBoundary.id()
        def outBoundaryId = fixtures.outBoundary.id()
        def outPolygon = fixtures.outPolygon as Polygon

        when:
        switch (actionCase) {
            case "createOutside":
                service.authorizeBoundaryCreate(directorId, outPolygon)
                break
            case "createNull":
                service.authorizeBoundaryCreate(directorId, null)
                break
            case "updateOutside":
                service.authorizeBoundaryUpdate(directorId, outBoundaryId, outPolygon)
                break
            case "moveOutside":
                service.authorizeBoundaryUpdate(directorId, inBoundaryId, outPolygon)
                break
            case "updateNull":
                service.authorizeBoundaryUpdate(directorId, inBoundaryId, null)
                break
            case "deleteOutside":
                service.authorizeBoundaryDelete(directorId, outBoundaryId)
                break
            case "deleteMissing":
                service.authorizeBoundaryDelete(directorId, UUID.randomUUID())
                break
        }

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN

        where:
        actionLabel                                    | actionCase
        "director creates boundary outside region"     | "createOutside"
        "director creates boundary with null geometry" | "createNull"
        "director updates out-of-region boundary"      | "updateOutside"
        "director moves in-region boundary outside"    | "moveOutside"
        "director updates boundary with null geometry" | "updateNull"
        "director deletes out-of-region boundary"      | "deleteOutside"
        "director deletes missing boundary"            | "deleteMissing"
    }

    private Map persistEdgeCaseFixtures() {
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

        return [
                regionA              : regionA,
                admin                : admin,
                orgManager           : orgManager,
                orgAdmin             : orgAdmin,
                outsider             : outsider,
                agentA               : agentA,
                agentB               : agentB,
                privateOrg           : privateOrg,
                expiredOrg           : expiredOrg,
                deletedProject       : deletedProject,
                draftProject         : draftProject,
                virtualManagedProject: virtualManagedProject
        ]
    }

    @Unroll
    def "project visibility returns #expected when #viewerLabel views a #projectLabel"() {
        given: "projects and users"
        def fixtures = persistEdgeCaseFixtures()
        def viewerId = viewerKey ? (fixtures[viewerKey] as User).id() : null

        expect:
        service.canViewProject(viewerId, fixtures[projectKey]) == expected

        where:
        viewerLabel        | viewerKey    | projectLabel              | projectKey              | expected
        "anonymous user"   | null         | "deleted project"         | "deletedProject"        | false
        "global admin"     | "admin"      | "deleted project"         | "deletedProject"        | false
        "anonymous user"   | null         | "draft project"           | "draftProject"          | false
        "outsider"         | "outsider"   | "draft project"           | "draftProject"          | false
        "org manager"      | "orgManager" | "draft project"           | "draftProject"          | true
        "global admin"     | "admin"      | "draft project"           | "draftProject"          | true
        "in-region agent"  | "agentA"     | "virtual managed project" | "virtualManagedProject" | true
        "out-region agent" | "agentB"     | "virtual managed project" | "virtualManagedProject" | false
    }

    @Unroll
    def "expired organization visibility returns #expected for #viewerLabel"() {
        given: "an expired public organization"
        def fixtures = persistEdgeCaseFixtures()
        def viewerId = viewerKey ? (fixtures[viewerKey] as User).id() : null

        expect:
        service.canViewOrganization(viewerId, fixtures.expiredOrg as Organization) == expected

        where:
        viewerLabel      | viewerKey  | expected
        "anonymous user" | null       | false
        "outsider"       | "outsider" | false
        "global admin"   | "admin"    | true
    }

    @Unroll
    def "isSystemAdmin returns #expected for #callerLabel"() {
        given: "users"
        def fixtures = persistEdgeCaseFixtures()
        def callerId = callerKey ? (fixtures[callerKey] as User).id() : null

        expect:
        service.isSystemAdmin(callerId) == expected

        where:
        callerLabel      | callerKey  | expected
        "anonymous user" | null       | false
        "outsider"       | "outsider" | false
        "global admin"   | "admin"    | true
    }

    def "isSystemAdmin throws UNAUTHORIZED for a missing actor"() {
        when:
        service.isSystemAdmin(UUID.randomUUID())

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    def "org admin passes user management check when org user management is allowed"() {
        given: "a private organization and an org admin"
        def fixtures = persistEdgeCaseFixtures()

        when:
        service.authorizeOrganizationAdmin((fixtures.orgAdmin as User).id(), (fixtures.privateOrg as Organization).id(), true)

        then:
        noExceptionThrown()
    }

    def "org manager is denied when org user management is allowed"() {
        given: "a private organization and an org manager"
        def fixtures = persistEdgeCaseFixtures()

        when:
        service.authorizeOrganizationAdmin((fixtures.orgManager as User).id(), (fixtures.privateOrg as Organization).id(), true)

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN
    }

    @Unroll
    def "missing actor receives UNAUTHORIZED from #guardName"() {
        given: "target records"
        def fixtures = persistEdgeCaseFixtures()
        UUID missingActorId = UUID.randomUUID()

        when:
        switch (guardName) {
            case "canManageShift":
                service.canManageShift(missingActorId, fixtures.virtualManagedProject)
                break
            case "authorizeOrganizationUpdate":
                service.authorizeOrganizationUpdate(missingActorId, (fixtures.privateOrg as Organization).id())
                break
            case "authorizeOrganizationVerification":
                service.authorizeOrganizationVerification(missingActorId)
                break
            case "authorizeRegionDirector":
                service.authorizeRegionDirector(missingActorId, (fixtures.regionA as AdministrativeRegion).id())
                break
            case "evaluateProjectCreationByUser":
                service.evaluateProjectCreationByUser(missingActorId, fixtures.draftProject)
                break
        }

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED

        where:
        guardName << [
                "canManageShift",
                "authorizeOrganizationUpdate",
                "authorizeOrganizationVerification",
                "authorizeRegionDirector",
                "evaluateProjectCreationByUser"
        ]
    }

    @Unroll
    def "self or user manager check allows #callerLabel to access target user"() {
        when:
        service.authorizeSelfOrUserManager(callerId, TestPersonas.VICTOR_VOLUNTEER.id)

        then:
        noExceptionThrown()

        where:
        callerLabel    | callerId
        "self"         | TestPersonas.VICTOR_VOLUNTEER.id
        "global admin" | TestPersonas.SARAH_ADMIN.id
    }

    def "self or user manager check rejects a missing actor with UNAUTHORIZED"() {
        given:
        UUID missingActorId = UUID.randomUUID()

        when:
        service.authorizeSelfOrUserManager(missingActorId, missingActorId)

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    @Unroll
    def "organization user view allows #callerLabel in the same organization"() {
        when:
        service.authorizeOrganizationUserView(callerId, TestPersonas.LUKE_LOCAL_MEMBER.id, TestPersonas.DENVER_METRO_BRANCH_ID)

        then:
        noExceptionThrown()

        where:
        callerLabel | callerId
        "self"      | TestPersonas.LUKE_LOCAL_MEMBER.id
        "org admin" | TestPersonas.LEO_LOCAL_ADMIN.id
    }

    @Unroll
    def "organization user view rejects #callerLabel with #expectedStatus"() {
        given:
        UUID missingActorId = UUID.randomUUID()
        UUID callerId = useMissing ? missingActorId : TestPersonas.URSULA_UTAH_ADMIN.id
        UUID targetId = useMissing ? missingActorId : TestPersonas.LUKE_LOCAL_MEMBER.id

        when:
        service.authorizeOrganizationUserView(callerId, targetId, TestPersonas.DENVER_METRO_BRANCH_ID)

        then:
        HttpStatusException ex = thrown()
        ex.status == expectedStatus

        where:
        callerLabel       | useMissing | expectedStatus
        "missing actor"   | true       | HttpStatus.UNAUTHORIZED
        "cross-org admin" | false      | HttpStatus.FORBIDDEN
    }

    @Unroll
    def "region user view allows #callerLabel in the same region"() {
        when:
        service.authorizeRegionUserView(callerId, TestPersonas.CONNIE_COUNTY_AGENT.id, TestPersonas.DENVER_COUNTY_REGION_ID)

        then:
        noExceptionThrown()

        where:
        callerLabel       | callerId
        "self"            | TestPersonas.CONNIE_COUNTY_AGENT.id
        "region director" | TestPersonas.DANIEL_COUNTY_DIRECTOR.id
    }

    @Unroll
    def "region user view rejects #callerLabel with #expectedStatus"() {
        given:
        UUID missingActorId = UUID.randomUUID()
        UUID callerId = useMissing ? missingActorId : TestPersonas.CONNIE_COUNTY_AGENT.id
        UUID targetId = useMissing ? missingActorId : TestPersonas.DANIEL_COUNTY_DIRECTOR.id

        when:
        service.authorizeRegionUserView(callerId, targetId, TestPersonas.DENVER_COUNTY_REGION_ID)

        then:
        HttpStatusException ex = thrown()
        ex.status == expectedStatus

        where:
        callerLabel     | useMissing | expectedStatus
        "missing actor" | true       | HttpStatus.UNAUTHORIZED
        "region agent"  | false      | HttpStatus.FORBIDDEN
    }

    private Map persistDemotedActorFixtures() {
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

        return [
                polygon     : polygon,
                region      : region,
                project     : project,
                demotedUser : demotedUser,
                demotedAgent: demotedAgent,
                otherMember : otherMember,
                insidePoint : insidePoint
        ]
    }

    @Unroll
    def "demoted standard user with stale region director assignment is denied #actionLabel"() {
        given: "a region project and a demoted standard user with a stale director row"
        def fixtures = persistDemotedActorFixtures()
        def userId = (fixtures.demotedUser as User).id()
        def regionId = (fixtures.region as AdministrativeRegion).id()

        when:
        switch (actionCase) {
            case "approval":
                service.authorizeRegionalAdminApproval(userId, fixtures.project.id())
                break
            case "locationCreate":
                service.authorizeLocationCreate(userId, fixtures.insidePoint)
                break
            case "boundaryCreate":
                service.authorizeBoundaryCreate(userId, fixtures.polygon as Polygon)
                break
            case "regionDirector":
                service.authorizeRegionDirector(userId, regionId)
                break
            case "regionUserView":
                service.authorizeRegionUserView(userId, (fixtures.otherMember as User).id(), regionId)
                break
        }

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN

        where:
        actionLabel              | actionCase
        "regional approval"      | "approval"
        "location creation"      | "locationCreate"
        "boundary creation"      | "boundaryCreate"
        "region director access" | "regionDirector"
        "region user view"       | "regionUserView"
    }

    @Unroll
    def "region agent with stale region director assignment is denied #actionLabel"() {
        given: "a region and a region agent with a stale director row"
        def fixtures = persistDemotedActorFixtures()
        def agentId = (fixtures.demotedAgent as User).id()
        def regionId = (fixtures.region as AdministrativeRegion).id()

        when:
        switch (actionCase) {
            case "regionDirector":
                service.authorizeRegionDirector(agentId, regionId)
                break
            case "regionUserView":
                service.authorizeRegionUserView(agentId, (fixtures.otherMember as User).id(), regionId)
                break
        }

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN

        where:
        actionLabel              | actionCase
        "region director access" | "regionDirector"
        "region user view"       | "regionUserView"
    }

    private Map persistRegionAgentBoundaryFixtures() {
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

        return [
                regionB                : regionB,
                org                    : org,
                agent                  : agent,
                director               : director,
                memberAgent            : memberAgent,
                outsidePhysicalProject : outsidePhysicalProject,
                virtualProjectInRegionA: virtualProjectInRegionA
        ]
    }

    def "non-member region agent creates a virtual project in their own region as PENDING"() {
        given: "a region agent and a virtual project in their region"
        def fixtures = persistRegionAgentBoundaryFixtures()

        expect:
        service.evaluateProjectCreationByUser((fixtures.agent as User).id(), fixtures.virtualProjectInRegionA) == ProjectStatus.PENDING
    }

    @Unroll
    def "regional #roleLabel cannot assign an out-of-region managing region"() {
        given: "a regional actor in region A and an unrelated region B"
        def fixtures = persistRegionAgentBoundaryFixtures()
        def actorId = (fixtures[actorKey] as User).id()

        expect:
        service.canAssignManagingRegion(actorId, (fixtures.org as Organization).id(), (fixtures.regionB as AdministrativeRegion).id()) == false

        where:
        roleLabel  | actorKey
        "agent"    | "agent"
        "director" | "director"
    }

    def "member region agent without org manager privileges is denied creating an out-of-region physical project"() {
        given: "a region agent who is an org member and a physical project outside their region"
        def fixtures = persistRegionAgentBoundaryFixtures()

        when:
        service.evaluateProjectCreationByUser((fixtures.memberAgent as User).id(), fixtures.outsidePhysicalProject)

        then:
        HttpStatusException boundaryEx = thrown()
        verifyAll(boundaryEx) {
            status == HttpStatus.FORBIDDEN
            message == "Region Agents cannot post outside their boundaries without Org Manager privileges"
        }
    }

    def "authorizeSystemAdmin allows a global admin"() {
        given: "a global admin"
        def admin = saveUser(UserRole.GLOBAL_ADMIN)

        when:
        service.authorizeSystemAdmin(admin.id())

        then:
        noExceptionThrown()
    }

    @Unroll
    def "authorizeSystemAdmin denies #role"() {
        given: "a non-admin user"
        def user = saveUser(role)

        when:
        service.authorizeSystemAdmin(user.id())

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN

        where:
        role << [UserRole.STANDARD_USER, UserRole.REGION_DIRECTOR]
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

    private Map persistShiftAndExpiryFixtures() {
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

        return [
                project          : project,
                futureVerifiedOrg: futureVerifiedOrg,
                manager          : manager,
                sponsor          : sponsor,
                outsider         : outsider,
                director         : director,
                orphanShift      : orphanShift
        ]
    }

    def "anonymous caller can view a public organization whose verification expires in the future"() {
        given: "a public organization with future verification expiration"
        def fixtures = persistShiftAndExpiryFixtures()

        expect:
        service.canViewOrganization(null, fixtures.futureVerifiedOrg as Organization) == true
    }

    @Unroll
    def "canManageShift returns #expected for #callerLabel"() {
        given: "a project and users"
        def fixtures = persistShiftAndExpiryFixtures()
        def callerId = (fixtures[callerKey] as User).id()

        expect:
        service.canManageShift(callerId, fixtures.project) == expected

        where:
        callerLabel       | callerKey  | expected
        "org manager"     | "manager"  | true
        "project sponsor" | "sponsor"  | true
        "outsider"        | "outsider" | false
    }

    @Unroll
    def "authorizeShiftManagement allows #callerLabel"() {
        given: "a project and authorized user"
        def fixtures = persistShiftAndExpiryFixtures()

        when:
        service.authorizeShiftManagement((fixtures[callerKey] as User).id(), fixtures.project)

        then:
        noExceptionThrown()

        where:
        callerLabel       | callerKey
        "org manager"     | "manager"
        "project sponsor" | "sponsor"
    }

    @Unroll
    def "authorizeShiftManagement throws #expectedStatus for #callerLabel"() {
        given: "a project and caller"
        def fixtures = persistShiftAndExpiryFixtures()
        UUID callerId = callerKey ? (fixtures[callerKey] as User).id() : UUID.randomUUID()

        when:
        service.authorizeShiftManagement(callerId, fixtures.project)

        then:
        HttpStatusException ex = thrown()
        ex.status == expectedStatus

        where:
        callerLabel     | callerKey  | expectedStatus
        "outsider"      | "outsider" | HttpStatus.FORBIDDEN
        "missing actor" | null       | HttpStatus.UNAUTHORIZED
    }

    def "canViewShift returns false for anonymous caller on a shift without a project"() {
        given: "an orphan shift"
        def fixtures = persistShiftAndExpiryFixtures()

        expect:
        service.canViewShift(null, fixtures.orphanShift as Shift) == false
    }

    def "authorizeShiftView throws UNAUTHORIZED for anonymous caller on a shift without a project"() {
        given: "an orphan shift"
        def fixtures = persistShiftAndExpiryFixtures()

        when:
        service.authorizeShiftView(null, fixtures.orphanShift as Shift)

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    def "authorizeLocationCreate throws FORBIDDEN when region director passes a null point"() {
        given: "a region director"
        def fixtures = persistShiftAndExpiryFixtures()

        when:
        service.authorizeLocationCreate((fixtures.director as User).id(), null)

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN
    }

    private Map persistUnsavedAndNullGuardFixtures() {
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

        return [
                admin                   : admin,
                standardUser            : standardUser,
                director                : director,
                agent                   : agent,
                unsavedOrg              : unsavedOrg,
                projectWithNullGeomLoc  : projectWithNullGeomLoc,
                projectWithoutOrg       : projectWithoutOrg,
                projectWithNullLocations: projectWithNullLocations,
                projectWithUnsavedRegion: projectWithUnsavedRegion,
                projectWithUnsavedOrg   : projectWithUnsavedOrg,
                virtualWithUnsavedRegion: virtualWithUnsavedRegion
        ]
    }

    def "areAllLocationsInOrgRegion returns false when a location has null geometry"() {
        given: "a project with a location missing geometry"
        def fixtures = persistUnsavedAndNullGuardFixtures()

        expect:
        service.areAllLocationsInOrgRegion(fixtures.projectWithNullGeomLoc, orgId) == false
    }

    @Unroll
    def "evaluateProjectCreationByUser returns PENDING for global admin when project has #caseLabel"() {
        given: "a global admin and partial project"
        def fixtures = persistUnsavedAndNullGuardFixtures()

        expect:
        service.evaluateProjectCreationByUser(fixtures.admin as User, fixtures[projectKey]) == ProjectStatus.PENDING

        where:
        caseLabel        | projectKey
        "no organization"| "projectWithoutOrg"
        "null locations" | "projectWithNullLocations"
    }

    @Unroll
    def "canModifyProject returns false when #caseLabel"() {
        given: "actors and projects with missing or unsaved organizations"
        def fixtures = persistUnsavedAndNullGuardFixtures()

        expect:
        service.canModifyProject(fixtures[actorKey] as User, fixtures[projectKey]) == false

        where:
        caseLabel                                          | actorKey       | projectKey
        "director checks project without organization"     | "director"     | "projectWithoutOrg"
        "standard user checks project with unsaved org"    | "standardUser" | "projectWithUnsavedOrg"
    }

    @Unroll
    def "canViewProject returns false when #caseLabel"() {
        given: "actors and partial or unsaved projects"
        def fixtures = persistUnsavedAndNullGuardFixtures()

        expect:
        service.canViewProject((fixtures[actorKey] as User).id(), fixtures[projectKey]) == false

        where:
        caseLabel                                           | actorKey       | projectKey
        "standard user views project without organization"  | "standardUser" | "projectWithoutOrg"
        "standard user views project with unsaved org"      | "standardUser" | "projectWithUnsavedOrg"
        "agent views project without organization"          | "agent"        | "projectWithoutOrg"
        "agent views virtual project with unsaved region"   | "agent"        | "virtualWithUnsavedRegion"
    }

    @Unroll
    def "canManageShift returns false when standard user checks project with #caseLabel"() {
        given: "a standard user and projects without saved organizations"
        def fixtures = persistUnsavedAndNullGuardFixtures()

        expect:
        service.canManageShift(fixtures.standardUser as User, fixtures[projectKey]) == false

        where:
        caseLabel            | projectKey
        "no organization"    | "projectWithoutOrg"
        "unsaved org"        | "projectWithUnsavedOrg"
    }

    def "canViewOrganization returns false for an unsaved organization"() {
        given: "a standard user and an unsaved organization"
        def fixtures = persistUnsavedAndNullGuardFixtures()

        expect:
        service.canViewOrganization((fixtures.standardUser as User).id(), fixtures.unsavedOrg as Organization) == false
    }

    @Unroll
    def "authorizeProjectCreation throws FORBIDDEN when #caseLabel"() {
        given: "an actor and a project missing saved references"
        def fixtures = persistUnsavedAndNullGuardFixtures()

        when:
        service.authorizeProjectCreation(fixtures[actorKey] as User, fixtures[projectKey])

        then:
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN

        where:
        caseLabel                                                 | actorKey       | projectKey
        "standard user submits project without organization"      | "standardUser" | "projectWithoutOrg"
        "region agent submits virtual project with unsaved region"| "agent"        | "projectWithUnsavedRegion"
    }

    private Map persistDirectActiveFixtures() {
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

        def insidePoint = dataBuilder.geometryFactory.createPoint(new Coordinate(-104.9903, 39.7392))
        insidePoint.setSRID(4326)

        return [
                polygon             : polygon,
                verifiedOrg         : verifiedOrg,
                unverifiedOrg       : unverifiedOrg,
                privateVerifiedOrg  : privateVerifiedOrg,
                loc                 : loc,
                outsideLoc          : outsideLoc,
                boundary            : boundary,
                admin               : admin,
                orgManager          : orgManager,
                verifiedMember      : verifiedMember,
                unverifiedOrgManager: unverifiedOrgManager,
                agent               : agent,
                standardUser        : standardUser,
                insidePoint         : insidePoint
        ]
    }

    @Unroll
    def "evaluateProjectCreationByUser returns #expectedStatus for #actorKey with #orgKey at #siteKey"() {
        given: "users, orgs, and project locations"
        def fixtures = persistDirectActiveFixtures()
        def actor = fixtures[actorKey] as User
        def org = fixtures[orgKey] as Organization
        def site = fixtures[siteKey] as Location
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Creation Status Project")
                .withDescription("Physical project submitted for status check.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(site)
                .build()

        expect: "evaluated creation status matches"
        service.evaluateProjectCreationByUser(actor.id(), project) == expectedStatus

        where:
        actorKey               | orgKey          | siteKey      | expectedStatus
        "orgManager"           | "verifiedOrg"   | "loc"        | ProjectStatus.ACTIVE
        "verifiedMember"       | "verifiedOrg"   | "loc"        | ProjectStatus.PENDING
        "orgManager"           | "verifiedOrg"   | "outsideLoc" | ProjectStatus.PENDING
        "agent"                | "unverifiedOrg" | "loc"        | ProjectStatus.ACTIVE
        "unverifiedOrgManager" | "unverifiedOrg" | "loc"        | ProjectStatus.PENDING
    }

    def "canManageShift returns true for global admin on a project"() {
        given: "a global admin and a pending project"
        def fixtures = persistDirectActiveFixtures()
        def project = dataBuilder.project()
                .forOrg(fixtures.verifiedOrg as Organization)
                .withTitle("Manager Active Project")
                .withDescription("Physical project submitted by verified org manager.")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(fixtures.loc as Location)
                .build()

        expect: "global admin can manage shifts"
        service.canManageShift((fixtures.admin as User).id(), project) == true
    }

    @Unroll
    def "canViewOrganization returns false for anonymous caller on #orgLabel"() {
        given: "an organization"
        def fixtures = persistDirectActiveFixtures()
        def org = fixtures[orgKey] as Organization

        expect: "anonymous caller cannot view the organization"
        service.canViewOrganization(null, org) == false

        where:
        orgLabel                 | orgKey
        "unverified public org"  | "unverifiedOrg"
        "private verified org"   | "privateVerifiedOrg"
    }

    @Unroll
    def "global admin passes #guardName guard without exception"() {
        given: "admin actor and target resources"
        def fixtures = persistDirectActiveFixtures()
        def adminId = (fixtures.admin as User).id()

        when: "global admin invokes #guardName"
        switch (guardName) {
            case "authorizeOrganizationAdmin":
                service.authorizeOrganizationAdmin(adminId, (fixtures.verifiedOrg as Organization).id())
                break
            case "authorizeLocationCreate":
                service.authorizeLocationCreate(adminId, fixtures.insidePoint)
                break
            case "authorizeLocationUpdate":
                service.authorizeLocationUpdate(adminId, (fixtures.loc as Location).id(), fixtures.insidePoint)
                break
            case "authorizeBoundaryCreate":
                service.authorizeBoundaryCreate(adminId, fixtures.polygon as Polygon)
                break
            case "authorizeBoundaryUpdate":
                service.authorizeBoundaryUpdate(adminId, fixtures.boundary.id(), fixtures.polygon as Polygon)
                break
            case "authorizeUserManager":
                service.authorizeUserManager(adminId)
                break
        }

        then: "no exception is thrown"
        noExceptionThrown()

        where:
        guardName << [
                "authorizeOrganizationAdmin",
                "authorizeLocationCreate",
                "authorizeLocationUpdate",
                "authorizeBoundaryCreate",
                "authorizeBoundaryUpdate",
                "authorizeUserManager"
        ]
    }

    @Unroll
    def "standard user receives FORBIDDEN from #guardName guard"() {
        given: "a standard user and target resources"
        def fixtures = persistDirectActiveFixtures()
        def userId = (fixtures.standardUser as User).id()

        when: "standard user invokes #guardName"
        switch (guardName) {
            case "authorizeLocationCreate":
                service.authorizeLocationCreate(userId, fixtures.insidePoint)
                break
            case "authorizeBoundaryCreate":
                service.authorizeBoundaryCreate(userId, fixtures.polygon as Polygon)
                break
            case "authorizeUserManager":
                service.authorizeUserManager(userId)
                break
        }

        then: "FORBIDDEN is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN

        where:
        guardName << [
                "authorizeLocationCreate",
                "authorizeBoundaryCreate",
                "authorizeUserManager"
        ]
    }
}
