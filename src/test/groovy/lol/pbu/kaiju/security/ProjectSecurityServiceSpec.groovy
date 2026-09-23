package lol.pbu.kaiju.security

import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.SecurityQueryRepository
import lol.pbu.kaiju.repository.UserRepository
import spock.lang.Shared

import java.time.OffsetDateTime

@MicronautTest(transactional = true)
class ProjectSecurityServiceSpec extends BaseControllerSpec {

    @Inject
    ProjectSecurityService service

    @Inject
    UserRepository userRepository

    @Inject
    SecurityQueryRepository queryRepository

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

    def "authorizeRegionalAdminApproval allows REGION_DIRECTOR to approve virtual project when org is in their region"() {
        given: "a region and an organization mapped to it"
        def regId = UUID.randomUUID()
        def vOrgId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Director Region', ST_GeogFromText('POLYGON((-105.1 39.7, -104.7 39.7, -104.7 39.6, -105.1 39.6, -105.1 39.7))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Virtual Org', true, 'VERIFIED')", vOrgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", vOrgId, regId)

        and: "a virtual project belonging to that organization (zero locations in project_locations)"
        def vProjId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Virtual Project', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, vProjId, vOrgId)

        and: "a user who is REGION_DIRECTOR of that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", director.id(), regId)

        when: "the REGION_DIRECTOR attempts to approve the virtual project"
        service.authorizeRegionalAdminApproval(director.id(), vProjId)

        then: "approval succeeds without throwing an exception"
        noExceptionThrown()
    }

    def "authorizeRegionalAdminApproval throws FORBIDDEN when REGION_DIRECTOR attempts to approve virtual project outside their region"() {
        given: "a region and an organization mapped to a different region"
        def directorRegId = UUID.randomUUID()
        def otherRegId = UUID.randomUUID()
        def vOrgId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Director Region', ST_GeogFromText('POLYGON((-105.1 39.7, -104.7 39.7, -104.7 39.6, -105.1 39.6, -105.1 39.7))'))", directorRegId)
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Other Region', ST_GeogFromText('POLYGON((-105.1 39.7, -104.7 39.7, -104.7 39.6, -105.1 39.6, -105.1 39.7))'))", otherRegId)
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Other Org', true)", vOrgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", vOrgId, otherRegId)

        and: "a virtual project belonging to that organization"
        def vProjId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Other Virtual Project', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, vProjId, vOrgId)

        and: "a user who is REGION_DIRECTOR of the unrelated region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", director.id(), directorRegId)

        when:
        service.authorizeRegionalAdminApproval(director.id(), vProjId)

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.FORBIDDEN
    }

    def "authorizeRegionalAdminApproval throws FORBIDDEN when REGION_AGENT attempts to approve a virtual project"() {
        given: "a virtual project with 0 locations"
        def vProjId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Agent Virtual Project', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, vProjId, orgId)

        and: "a REGION_AGENT"
        def agent = saveUser(UserRole.REGION_AGENT)

        when:
        service.authorizeRegionalAdminApproval(agent.id(), vProjId)

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.FORBIDDEN
        e.message.contains("virtual")
    }

    def "hasJurisdictionOverAllProjectLocations recognizes REGION_DIRECTOR in addition to REGION_AGENT"() {
        given: "a region, a project with a location inside that region, and a REGION_DIRECTOR"
        def regId = UUID.randomUUID()
        def locId = UUID.randomUUID()
        def pId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Director Loc Region', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Loc', 'St', 'City', 'US', ST_GeographyFromText('POINT(-104.9903 39.7392)'))", locId)
        executeUpdate("INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at) VALUES (?, ?, 'Loc Project', 'Desc', 'STANDARD', 'PENDING', NOW())", pId, orgId)
        executeUpdate("INSERT INTO project_locations (project_id, location_id) VALUES (?, ?)", pId, locId)

        and: "a REGION_DIRECTOR assigned to that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", director.id(), regId)

        expect:
        queryRepository.hasJurisdictionOverAllProjectLocations(director.id(), pId) == true
        service.canReassignProject(director.id(), new lol.pbu.kaiju.domain.Project(pId, null, null, "Loc Project", "Desc", lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING, OffsetDateTime.now(), null, null, [], [])) == true
    }

    def "canModifyProject allows REGION_DIRECTOR to modify a virtual project in their region"() {
        given: "a region, verified organization in that region, and virtual project"
        def regId = UUID.randomUUID()
        def vOrgId = UUID.randomUUID()
        def vProjId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Mod Region', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Mod Org', true, 'VERIFIED')", vOrgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", vOrgId, regId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Virtual Mod Proj', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, vProjId, vOrgId)

        and: "a REGION_DIRECTOR of that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", director.id(), regId)

        and: "domain entity representation"
        def orgEntity = new lol.pbu.kaiju.domain.Organization(vOrgId, "Mod Org", null, null, true, lol.pbu.kaiju.model.VerificationStatus.VERIFIED, null, [])
        def projEntity = new lol.pbu.kaiju.domain.Project(vProjId, orgEntity, null, "Virtual Mod Proj", "Desc", lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING, OffsetDateTime.now(), null, null, [], [])

        expect:
        service.canModifyProject(director.id(), projEntity) == true
    }

    def "canModifyProject denies REGION_DIRECTOR on virtual project outside their region"() {
        given: "a region, organization in a different region, and virtual project"
        def dirRegId = UUID.randomUUID()
        def otherRegId = UUID.randomUUID()
        def vOrgId = UUID.randomUUID()
        def vProjId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Dir Reg', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", dirRegId)
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Other Reg', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", otherRegId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Other Org', true, 'VERIFIED')", vOrgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", vOrgId, otherRegId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Virtual Mod Proj', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, vProjId, vOrgId)

        and: "a REGION_DIRECTOR of Dir Reg"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", director.id(), dirRegId)

        def orgEntity = new lol.pbu.kaiju.domain.Organization(vOrgId, "Other Org", null, null, true, lol.pbu.kaiju.model.VerificationStatus.VERIFIED, null, [])
        def projEntity = new lol.pbu.kaiju.domain.Project(vProjId, orgEntity, null, "Virtual Mod Proj", "Desc", lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING, OffsetDateTime.now(), null, null, [], [])

        expect:
        service.canModifyProject(director.id(), projEntity) == false
    }

    def "isVirtualProjectInDirectorJurisdiction enforces managing_region_id precedence over organization_regions"() {
        given: "two regions A and B"
        def regAId = UUID.randomUUID()
        def regBId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Region A', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regAId)
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Region B', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regBId)

        and: "an organization with presence in both Region A and Region B"
        def orgMultiId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Multi Org', true, 'VERIFIED')", orgMultiId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgMultiId, regAId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgMultiId, regBId)

        and: "a virtual project explicitly assigned to Region A via managing_region_id"
        def projAId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, managing_region_id, title, description, project_type, status, created_at)
            VALUES (?, ?, ?, 'Project specifically in Region A', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projAId, orgMultiId, regAId)

        and: "directors for Region A and Region B"
        def dirA = saveUser(UserRole.REGION_DIRECTOR)
        def dirB = saveUser(UserRole.REGION_DIRECTOR)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", dirA.id(), regAId)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", dirB.id(), regBId)

        expect: "Director A has jurisdiction because managing_region_id matches Region A"
        queryRepository.isVirtualProjectInDirectorJurisdiction(dirA.id(), projAId) == true

        and: "Director B is denied despite org presence in Region B, because managing_region_id takes precedence"
        queryRepository.isVirtualProjectInDirectorJurisdiction(dirB.id(), projAId) == false
    }

    def "isVirtualProjectInDirectorJurisdiction rejects unverified organizations"() {
        given: "a region and an UNVERIFIED organization in that region"
        def regId = UUID.randomUUID()
        def unverifiedOrgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Unverified Reg', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Unverified Org', true, 'UNVERIFIED')", unverifiedOrgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", unverifiedOrgId, regId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Unverified Org Proj', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, unverifiedOrgId)

        and: "a director of that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", director.id(), regId)

        expect: "unverified organization virtual project cannot be approved by director"
        queryRepository.isVirtualProjectInDirectorJurisdiction(director.id(), projId) == false
    }

    def "canModifyProject allows REGION_DIRECTOR to modify a physical project with locations inside their region"() {
        given: "a region, verified org, location, and physical project"
        def regId = UUID.randomUUID()
        def orgId = UUID.randomUUID()
        def locId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Dir Phys Mod Reg', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Phys Mod Org', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Loc', 'St', 'Denver', 'US', ST_GeographyFromText('POINT(-104.9903 39.7392)'))", locId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Phys Mod Proj', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)
        executeUpdate("INSERT INTO project_locations (project_id, location_id) VALUES (?, ?)", projId, locId)

        and: "a REGION_DIRECTOR assigned to that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", director.id(), regId)

        and: "domain entity representation"
        def orgEntity = new lol.pbu.kaiju.domain.Organization(orgId, "Phys Mod Org", null, null, true, lol.pbu.kaiju.model.VerificationStatus.VERIFIED, null, [])
        def projEntity = new lol.pbu.kaiju.domain.Project(projId, orgEntity, null, "Phys Mod Proj", "Desc", lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING, OffsetDateTime.now(), null, null, [], [])

        expect:
        service.canModifyProject(director.id(), projEntity) == true
    }

    def "canReassignProject allows REGION_DIRECTOR to reassign a virtual project in their region"() {
        given: "a region, verified org, and virtual project"
        def regId = UUID.randomUUID()
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Dir VReassign Reg', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'VReassign Org', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgId, regId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'VReassign Proj', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)

        and: "a REGION_DIRECTOR of that region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", director.id(), regId)

        and: "a domain entity"
        def orgEntity = new lol.pbu.kaiju.domain.Organization(orgId, "VReassign Org", null, null, true, lol.pbu.kaiju.model.VerificationStatus.VERIFIED, null, [])
        def projEntity = new lol.pbu.kaiju.domain.Project(projId, orgEntity, null, "VReassign Proj", "Desc", lol.pbu.kaiju.model.ProjectType.STANDARD, lol.pbu.kaiju.model.ProjectStatus.PENDING, OffsetDateTime.now(), null, null, [], [])

        expect:
        service.canReassignProject(director.id(), projEntity) == true
    }

    def "authorizeRegionalAdminApproval throws FORBIDDEN for REGION_DIRECTOR on physical project outside their region"() {
        given: "a region, an out-of-region location, and a physical project"
        def regId = UUID.randomUUID()
        def orgId = UUID.randomUUID()
        def locId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Director Reg', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Far Loc', 'St', 'Boulder', 'US', ST_GeographyFromText('POINT(-105.2705 40.0150)'))", locId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Far Phys Proj', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)
        executeUpdate("INSERT INTO project_locations (project_id, location_id) VALUES (?, ?)", projId, locId)

        and: "a REGION_DIRECTOR assigned to Denver region"
        def director = saveUser(UserRole.REGION_DIRECTOR)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", director.id(), regId)

        when: "attempting to approve out-of-region physical project"
        service.authorizeRegionalAdminApproval(director.id(), projId)

        then: "a 403 Forbidden is thrown"
        HttpStatusException e = thrown()
        e.status == HttpStatus.FORBIDDEN
    }
}
