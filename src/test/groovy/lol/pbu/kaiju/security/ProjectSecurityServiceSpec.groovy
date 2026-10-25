package lol.pbu.kaiju.security

import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.SecurityQueryRepository
import org.locationtech.jts.geom.Coordinate

import java.util.UUID

@MicronautTest(transactional = false)
class ProjectSecurityServiceSpec extends BaseControllerSpec {

    @Inject
    ProjectSecurityService service

    @Inject
    SecurityQueryRepository queryRepository

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

    def "canModifyProject throws NOT_FOUND when user does not exist"() {
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

    def "canReassignProject throws NOT_FOUND when user does not exist"() {
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
        e.status == HttpStatus.NOT_FOUND
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
        Coordinate[] denverCoords = [
                new Coordinate(-105.1099, 39.7891),
                new Coordinate(-104.7432, 39.7912),
                new Coordinate(-104.7528, 39.6158),
                new Coordinate(-105.0536, 39.6137),
                new Coordinate(-105.1099, 39.7891)
        ]
        def region = dataBuilder.region()
                .withName("Denver Area")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(denverCoords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.7),
                new Coordinate(-104.7, 39.7),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.7)
        ]
        def region = dataBuilder.region()
                .withName("Director Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        service.authorizeRegionalAdminApproval(director.id(), project.id())

        then: "approval succeeds without throwing an exception"
        noExceptionThrown()
    }

    def "authorizeRegionalAdminApproval throws FORBIDDEN when REGION_DIRECTOR attempts to approve virtual project outside their region"() {
        given: "a region and an organization mapped to a different region"
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.7),
                new Coordinate(-104.7, 39.7),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.7)
        ]
        def directorRegion = dataBuilder.region()
                .withName("Director Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
                .persist()
        def otherRegion = dataBuilder.region()
                .withName("Other Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Director Loc Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Mod Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def dirRegion = dataBuilder.region()
                .withName("Dir Reg")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
                .persist()
        def otherRegion = dataBuilder.region()
                .withName("Other Reg")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def regA = dataBuilder.region()
                .withName("Region A")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
                .persist()
        def regB = dataBuilder.region()
                .withName("Region B")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Unverified Reg")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Dir Phys Mod Reg")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Dir VReassign Reg")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Director Reg")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Batch Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
        Coordinate[] coords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Assign Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(coords))
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
    }
}
