package lol.pbu.kaiju.controller

import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.TestFixtures
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.ProjectSearchCard
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.ProjectAuditLogRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.security.ProjectSecurityService
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Unroll

import java.security.Principal
import java.time.OffsetDateTime

import io.micronaut.http.HttpStatus
import static io.micronaut.http.HttpStatus.BAD_REQUEST
import static io.micronaut.http.HttpStatus.FORBIDDEN
import static io.micronaut.http.HttpStatus.NOT_FOUND
import static lol.pbu.kaiju.model.ProjectStatus.ACTIVE
import static lol.pbu.kaiju.model.ProjectStatus.DRAFT
import static lol.pbu.kaiju.model.ProjectStatus.PENDING
import static lol.pbu.kaiju.model.ProjectType.STANDARD
import static lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED

class ProjectControllerSpec extends BaseControllerSpec {

    @Inject
    ProjectRepository projectRepository

    @Inject
    ProjectController projectController

    @Inject
    ProjectSecurityService realProjectSecurityService

    @Inject
    OrganizationRepository organizationRepository

    @Inject
    UserRepository userRepository

    @Inject
    ProjectAuditLogRepository projectAuditLogRepository

    @Inject
    AdministrativeRegionRepository administrativeRegionRepository

    @Inject
    GeometryFactory geometryFactory

    def setupSpec() {
        executeUpdate("INSERT INTO users (id, email, role) VALUES ('00000000-0000-0000-0000-000000000000', 'test-principal@example.com', 'GLOBAL_ADMIN') ON CONFLICT DO NOTHING")
    }

    @Shared
    Principal testPrincipal = new Principal() { @Override String getName() { return "00000000-0000-0000-0000-000000000000" } }

    Principal createPrincipal(UUID userId) {
        new Principal() {
            @Override
            String getName() {
                return userId.toString()
            }
        }
    }

    @Shared
    Faker faker = new Faker()

    private Organization getRandomOrganization() {
        def orgRow = sql.firstRow("SELECT id, name FROM organizations LIMIT 1")
        if (!orgRow) {
            throw new IllegalStateException("No organizations found in database to link project to.")
        }
        new Organization(orgRow.id as UUID, orgRow.name as String, null, null, true, UNVERIFIED, null, [])
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid project"() {
        given: "a new valid project"
        def org = getRandomOrganization()
        def newProject = TestFixtures.createBasicProject(org as Organization, "Test Project ${faker.company().name()}" as String, "Test Description ${faker.lorem().paragraph()}" as String, STANDARD as ProjectType, DRAFT as ProjectStatus)

        when: "the project is added"
        Project saved = projectController.submitProject(newProject, testPrincipal)

        then: "the project is persisted with a generated ID"
        verifyAll {
            saved.id() != null
            saved.title() == newProject.title()
            saved.description() == newProject.description()
            saved.projectType() == newProject.projectType()
            saved.status() == newProject.status()
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM projects WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.title() == title
            saved.description() == description
        }
    }

    @Unroll

    def "CREATE | should throw HttpStatusException if organization is null"() {
        given: "a manual controller instance to bypass validation interceptors"
        def project = TestFixtures.createBasicProject(null, "Test Title", "Test Desc", STANDARD, DRAFT)
        def controller = new ProjectController(projectRepository, realProjectSecurityService, organizationRepository, userRepository, projectAuditLogRepository, administrativeRegionRepository, geometryFactory)

        when:
        controller.submitProject(project, testPrincipal)

        then:
        def e = thrown(HttpStatusException)
        e.status == BAD_REQUEST
        e.message == "Organization is required"
    }

    def "CREATE | should fail to save project with invalid data: #testCase"(String testCase, Project project) {
        when: "an attempt is made to add a project with invalid data"
        projectController.submitProject(project, testPrincipal)

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, project] << {
            def dummyOrg = new Organization(UUID.randomUUID(), "Dummy Org", null, null, true, UNVERIFIED, null, [])

            def validData = [organization: dummyOrg,
                             title       : "Valid Title",
                             description : "Valid Description",
                             projectType: STANDARD,
                             status     : DRAFT]

            def invalidCases = [[field: 'organization', value: null, caseName: "Null Organization"],
                                [field: 'title', value: null, caseName: "Null Title"],
                                [field: 'title', value: ' ', caseName: "Blank Title"],
                                [field: 'title', value: 'A' * 256, caseName: "Title Too Long"],
                                [field: 'description', value: null, caseName: "Null Description"],
                                [field: 'description', value: ' ', caseName: "Blank Description"],
                                [field: 'projectType', value: null, caseName: "Null Project Type"],
                                [field: 'status', value: null, caseName: "Null Status"]]

            return invalidCases.collect { invalidCase ->
                def props = new HashMap(validData)
                props[invalidCase.field] = invalidCase.value
                def proj = TestFixtures.createBasicProject(props.organization as Organization as Organization, props.title as String as String, props.description as String as String, props.projectType as ProjectType as ProjectType, props.status as ProjectStatus as ProjectStatus)
                [invalidCase.caseName, proj]
            }
        }()
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing project by ID"() {
        given: "an existing project"
        def org = getRandomOrganization()
        def project = projectController.submitProject(TestFixtures.createBasicProject(org as Organization, "Test Project Read" as String, "Description" as String, STANDARD as ProjectType, DRAFT as ProjectStatus), testPrincipal)
        UUID id = project.id()

        when: "the project is requested by its ID"
        def result = projectController.getProject(id)

        then: "the correct project is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
            result.get().title() == "Test Project Read"
        }
    }

    def "READ | should return empty for a non-existent project ID"() {
        when: "a non-existent project is requested"
        def result = projectController.getProject(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    def "READ | should retrieve projects by title"() {
        given: "an existing project's title from the database"
        def org = getRandomOrganization()
        def project = projectController.submitProject(TestFixtures.createBasicProject(org as Organization, "Searchable Title ${faker.number().digits(5)}" as String, "Description" as String, STANDARD as ProjectType, DRAFT as ProjectStatus), testPrincipal)
        def targetTitle = project.title()

        when: "projects are searched by this title"
        def page = projectController.getProjects(targetTitle, CursoredPageable.from(10, Sort.of(Sort.Order.asc("title"))))

        then: "the search returns a page containing the project"
        verifyAll {
            page != null
            page.content.any { it.title() == targetTitle }
        }
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing project"() {
        given: "an existing project"
        def org = getRandomOrganization()
        def project = projectController.submitProject(TestFixtures.createBasicProject(org as Organization, "Original Project Title" as String, "Original Description" as String, STANDARD as ProjectType, DRAFT as ProjectStatus), testPrincipal)
        UUID id = project.id()
        def newTitle = "Updated ${faker.book().title()}"
        def newDescription = "Updated Description ${faker.lorem().paragraph()}"
        def updateRequest = TestFixtures.createBasicProject(org as Organization, newTitle as String, newDescription as String, STANDARD as ProjectType, ACTIVE as ProjectStatus)

        when: "the project is updated"
        Project updated = projectController.updateProject(id, updateRequest, testPrincipal)

        then: "the returned project contains the updated data but status remains unchanged"
        verifyAll {
            updated.id() == id
            updated.title() == newTitle
            updated.description() == newDescription
            updated.status() == DRAFT
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT title, description, status FROM projects WHERE id = ?", [id])
        verifyAll(dbResult) {
            title == newTitle
            description == newDescription
            status == 'DRAFT'
        }
    }

    def "UPDATE | should fail to update a non-existent project"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def org = getRandomOrganization()
        def updateRequest = TestFixtures.createBasicProject(org as Organization, "New Title" as String, "New Description" as String, STANDARD as ProjectType, DRAFT as ProjectStatus)

        when: "an update is attempted"
        projectController.updateProject(nonExistentId, updateRequest, testPrincipal)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should reject reassignment to another organization when user is ORG_MANAGER"() {
        given: "two organizations Org A and Org B"
        def orgAId = UUID.randomUUID()
        def orgBId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org B', true)", orgBId)

        and: "a user who is an ORG_MANAGER of Org A only"
        def userId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "manager-a-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", userId, orgAId)

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Project in Org A', 'Desc', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

        and: "an update payload attempting to reassign the project to Org B"
        def orgB = new Organization(orgBId, "Org B", null, null, true, UNVERIFIED, null, [])
        def updateRequest = TestFixtures.createBasicProject(orgB, "Hijacked Title", "Hijacked Desc", STANDARD, DRAFT)

        when: "the Org Manager attempts to reassign the project to Org B"
        projectController.updateProject(projectId, updateRequest, createPrincipal(userId))

        then: "the request is rejected with 403 Forbidden"
        def e = thrown(HttpStatusException)
        e.status == FORBIDDEN
        e.message == "You do not have permission to reassign this project to another organization"

        and: "the project in the database remains assigned to Org A"
        def projectInDb = sql.firstRow("SELECT organization_id, title FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgAId
        projectInDb.title == 'Project in Org A'
    }

    def "UPDATE | should allow legitimate update by Org Manager preserving existing organization"() {
        given: "an organization Org A"
        def orgAId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)

        and: "a user who is an ORG_MANAGER of Org A"
        def userId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "manager-legit-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", userId, orgAId)

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Initial Title', 'Initial Desc', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

        and: "an update payload preserving Org A with updated title and description"
        def orgA = new Organization(orgAId, "Org A", null, null, true, UNVERIFIED, null, [])
        def updateRequest = TestFixtures.createBasicProject(orgA, "Legit Updated Title", "Legit Updated Desc", STANDARD, DRAFT)

        when: "the Org Manager updates the project"
        Project updated = projectController.updateProject(projectId, updateRequest, createPrincipal(userId))

        then: "the update succeeds"
        updated.id() == projectId
        updated.title() == "Legit Updated Title"
        updated.description() == "Legit Updated Desc"

        and: "the database reflects the updated fields while organization remains Org A"
        def projectInDb = sql.firstRow("SELECT organization_id, title, description FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgAId
        projectInDb.title == "Legit Updated Title"
        projectInDb.description == "Legit Updated Desc"
    }

    def "UPDATE | should fail validation when organization is null in payload"() {
        given: "an organization Org A"
        def orgAId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)

        and: "a user who is an ORG_MANAGER of Org A"
        def userId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "manager-null-org-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", userId, orgAId)

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Initial Title', 'Initial Desc', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

        and: "an update payload with null organization"
        def updateRequest = TestFixtures.createBasicProject(null, "Updated Title With Null Org", "Desc", STANDARD, DRAFT)

        when: "the Org Manager attempts to update the project with a null organization"
        projectController.updateProject(projectId, updateRequest, createPrincipal(userId))

        then: "validation fails because organization is required on Project"
        thrown(ValidationException)
    }

    def "UPDATE | should allow project reassignment to another organization when user is GLOBAL_ADMIN"() {
        given: "two organizations Org A and Org B"
        def orgAId = UUID.randomUUID()
        def orgBId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org B', true)", orgBId)

        and: "a GLOBAL_ADMIN user"
        def adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-${UUID.randomUUID()}@example.com".toString())

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Original Title', 'Original Desc', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

        and: "an update payload reassigning the project to Org B"
        def orgB = new Organization(orgBId, "Org B", null, null, true, UNVERIFIED, null, [])
        def updateRequest = TestFixtures.createBasicProject(orgB, "Admin Updated Title", "Admin Updated Desc", STANDARD, DRAFT)

        when: "the Global Admin reassigns the project to Org B"
        Project updated = projectController.updateProject(projectId, updateRequest, createPrincipal(adminId))

        then: "the update succeeds"
        updated.id() == projectId
        updated.title() == "Admin Updated Title"

        and: "the project is reassigned to Org B in the database"
        def projectInDb = sql.firstRow("SELECT organization_id, title FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgBId
        projectInDb.title == "Admin Updated Title"
    }

    def "UPDATE | should reject project reassignment even when user is manager of destination org"() {
        given: "two organizations Org A and Org B"
        def orgAId = UUID.randomUUID()
        def orgBId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org B', true)", orgBId)

        and: "a user who is an ORG_MANAGER of both Org A and Org B"
        def dualManagerId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", dualManagerId, "dual-mgr-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", dualManagerId, orgAId)
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", dualManagerId, orgBId)

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Dual Mgr Project', 'Desc', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

        and: "an update payload reassigning the project to Org B"
        def orgB = new Organization(orgBId, "Org B", null, null, true, UNVERIFIED, null, [])
        def updateRequest = TestFixtures.createBasicProject(orgB, "Reassigned by Dual Manager", "Desc", STANDARD, DRAFT)

        when: "the dual manager attempts to reassign the project to Org B"
        projectController.updateProject(projectId, updateRequest, createPrincipal(dualManagerId))

        then: "the update is rejected with 403 Forbidden because org managers cannot reassign projects"
        def e = thrown(HttpStatusException)
        e.status == FORBIDDEN
        e.message == "You do not have permission to reassign this project to another organization"

        and: "the project remains assigned to Org A in the database"
        def projectInDb = sql.firstRow("SELECT organization_id FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgAId
    }

    def "UPDATE | should throw 404 Not Found when privileged user reassigns project to non-existent organization"() {
        given: "an organization Org A"
        def orgAId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)

        and: "a GLOBAL_ADMIN user"
        def adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-${UUID.randomUUID()}@example.com".toString())

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Project to Reassign', 'Desc', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

        and: "an update payload with a non-existent destination organization"
        def nonExistentOrgId = UUID.randomUUID()
        def nonExistentOrg = new Organization(nonExistentOrgId, "Ghost Org", null, null, true, UNVERIFIED, null, [])
        def updateRequest = TestFixtures.createBasicProject(nonExistentOrg, "Ghost Org Project", "Desc", STANDARD, DRAFT)

        when: "the Global Admin reassigns the project to non-existent organization"
        projectController.updateProject(projectId, updateRequest, createPrincipal(adminId))

        then: "a 404 Not Found exception is thrown"
        def e = thrown(HttpStatusException)
        e.status == NOT_FOUND
        e.message == "Target organization not found"

        and: "the project in DB remains assigned to Org A"
        def projectInDb = sql.firstRow("SELECT organization_id FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgAId
    }

    def "UPDATE | should demote ACTIVE project status to PENDING when location is updated outside boundary"() {
        given: "a region and an organization bounded to it"
        def regionId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom) 
            VALUES (?, 'City of Denver', ST_GeogFromText('POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))'))
        """, regionId)

        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Denver Org', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgId, regionId)

        and: "an Org Manager for the organization"
        def userId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "manager-demote-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", userId, orgId)

        and: "an ACTIVE project belonging to the organization"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Active Denver Project', 'Desc', 'STANDARD', 'ACTIVE', NOW())
        """, projectId, orgId)

        and: "an update payload with a location outside the region (Boulder)"
        def geomFactory = new GeometryFactory(new PrecisionModel(), 4326)
        def outsidePoint = geomFactory.createPoint(new Coordinate(-105.2705, 40.0150))
        def outsideLoc = new Location(UUID.randomUUID(), "Boulder Loc", "123 Pearl St", "Boulder", "CO", "80302", "US", outsidePoint)
        def org = new Organization(orgId, "Denver Org", null, null, true, UNVERIFIED, null, [])
        def updateRequest = new Project(
                projectId,
                org,
                null,
                "Updated Active Project",
                "Updated Desc",
                STANDARD,
                ACTIVE,
                null,
                null,
                null,
                [outsideLoc],
                []
        )

        when: "the Org Manager updates the project with outside locations"
        Project updated = projectController.updateProject(projectId, updateRequest, createPrincipal(userId))

        then: "the project status is demoted to PENDING for regional review"
        updated.status() == PENDING

        and: "the status in database is PENDING"
        def projectInDb = sql.firstRow("SELECT status, title FROM projects WHERE id = ?", [projectId])
        projectInDb.status == 'PENDING'
        projectInDb.title == "Updated Active Project"
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing project"() {
        given: "a new project to be deleted"
        def org = getRandomOrganization()
        def tempProject = TestFixtures.createBasicProject(org as Organization, "Temporary Project to Delete" as String, "Temporary Description" as String, STANDARD as ProjectType, DRAFT as ProjectStatus)
        def saved = projectController.submitProject(tempProject, testPrincipal)
        UUID id = saved.id()
        assert projectRepository.existsById(id)

        when: "the project is deleted"
        projectController.deleteProject(id, testPrincipal)

        then: "the project no longer exists in the repository or database"
        verifyAll {
            !projectRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM projects WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent project"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted"
        projectController.deleteProject(nonExistentId, testPrincipal)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "SEARCH BY LOCATION | should successfully query projects by location point, returning closest locations first"() {
        given: "seed the database with test organization, projects, locations (closest to furthest), and active shifts"
        def uuids = [:].withDefault { UUID.randomUUID() }

        // SQL Templates Helper
        String idName = "id, name"
        def insertInto = { String table, String columns, String values ->
            "INSERT INTO ${table} (${columns}) VALUES (${values})"
        }

        String insertOrganizationSql = insertInto("organizations", "${idName}, is_public", "?, 'Distance Test Org', true")
        String insertProjectSql = insertInto("projects", "id, organization_id, title, description, project_type, status, created_at", "?, ?, ?, 'Description', 'STANDARD', 'ACTIVE', NOW()")
        String insertLocationSql = insertInto("locations", "${idName}, address_line, city, country_code, geom", "?, ?, ?, ?, ?, ST_GeographyFromText(?)")
        String insertProjectLocationSql = insertInto("project_locations", "project_id, location_id", "?, ?")
        String insertShiftSql = insertInto("shifts", "id, project_id, is_virtual, location_id, start_time, end_time", "?, ?, false, ?, NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 2 hours'")

        [
                [insertOrganizationSql, [uuids.organizationId]],

                // Projects
                [insertProjectSql, [uuids.projectIdA, uuids.organizationId, 'Project A']],
                [insertProjectSql, [uuids.projectIdB, uuids.organizationId, 'Project B']],
                [insertProjectSql, [uuids.projectIdC, uuids.organizationId, 'Project C']],
                [insertProjectSql, [uuids.projectIdD, uuids.organizationId, 'Project D']],
                [insertProjectSql, [uuids.projectIdE, uuids.organizationId, 'Project E']],

                /*
                validate point math with sql queries to database, ie
                SELECT
                    name,
                    ST_Distance(
                        geom,
                        ST_GeographyFromText('POINT(-104.9903 39.7392)')
                    ) / 1000.0 AS distance_km
                FROM locations
                ORDER BY distance_km ASC;
                 */

                // Locations (Reference point: POINT(-104.9903 39.7392))
                [insertLocationSql, [uuids.locA1, 'Location A1', '123 Closest St', 'Denver', 'US', 'POINT(-104.9903 39.7572)']], // ~2 km (1st closest)
                [insertLocationSql, [uuids.locB1, 'Location B1', '456 Second St', 'Denver', 'US', 'POINT(-104.9903 39.7842)']], // ~5 km (2nd closest)
                [insertLocationSql, [uuids.locA2, 'Location A2', '789 Third St', 'Denver', 'US', 'POINT(-104.9903 39.8292)']],  // ~10 km (3rd closest)
                [insertLocationSql, [uuids.locE1, 'Location E1', '101 Fourth St', 'Denver', 'US', 'POINT(-104.9903 39.8472)']], // ~12 km (4th closest)
                [insertLocationSql, [uuids.locC1, 'Location C1', '202 Fifth St', 'Denver', 'US', 'POINT(-104.9903 39.8742)']],  // ~15 km (5th closest)
                [insertLocationSql, [uuids.locA3, 'Location A3', '303 Far St', 'Denver', 'US', 'POINT(-104.9903 40.1892)']],    // ~50 km (outside)
                [insertLocationSql, [uuids.locD1, 'Location D1', '404 Far St', 'Denver', 'US', 'POINT(-104.9903 40.1892)']],    // ~50 km (outside)

                // Project Locations mappings
                [insertProjectLocationSql, [uuids.projectIdA, uuids.locA1]],
                [insertProjectLocationSql, [uuids.projectIdA, uuids.locA2]],
                [insertProjectLocationSql, [uuids.projectIdA, uuids.locA3]],
                [insertProjectLocationSql, [uuids.projectIdB, uuids.locB1]],
                [insertProjectLocationSql, [uuids.projectIdE, uuids.locE1]],
                [insertProjectLocationSql, [uuids.projectIdC, uuids.locC1]],
                [insertProjectLocationSql, [uuids.projectIdD, uuids.locD1]],

                // Active Shifts
                [insertShiftSql, [uuids.shiftA1, uuids.projectIdA, uuids.locA1]],
                [insertShiftSql, [uuids.shiftA2, uuids.projectIdA, uuids.locA2]],
                [insertShiftSql, [uuids.shiftA3, uuids.projectIdA, uuids.locA3]],
                [insertShiftSql, [uuids.shiftB1, uuids.projectIdB, uuids.locB1]],
                [insertShiftSql, [uuids.shiftE1, uuids.projectIdE, uuids.locE1]],
                [insertShiftSql, [uuids.shiftC1, uuids.projectIdC, uuids.locC1]],
                [insertShiftSql, [uuids.shiftD1, uuids.projectIdD, uuids.locD1]]
        ].each { List<Object> seedStatement -> executeUpdate(seedStatement[0] as String, *(seedStatement[1] as List)) }

        and: "a reference point at Denver center"
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)
        Point point = geometryFactory.createPoint(new Coordinate(-104.9903, 39.7392))

        when: "searching projects by location with 20km radius and a large page size to handle existing database records"
        Page<ProjectSearchCard> page = projectController.searchByLocation(point.getX(), point.getY(), 20000.0, Pageable.from(0, 100))

        then: "only active projects in range are returned sorted by closest location, with no duplicates per project"
        page != null
        List<ProjectSearchCard> testResults = page.content.findAll { it.projectId() in [uuids.projectIdA, uuids.projectIdB, uuids.projectIdC, uuids.projectIdD, uuids.projectIdE] }
        testResults.size() == 4

        // 1st: Project A (via Location A1 @ ~2km)
        testResults[0].projectId() == uuids.projectIdA
        testResults[0].locationId() == uuids.locA1

        // 2nd: Project B (via Location B1 @ ~5km)
        testResults[1].projectId() == uuids.projectIdB
        testResults[1].locationId() == uuids.locB1

        // 3rd: Project E (via Location E1 @ ~12km)
        testResults[2].projectId() == uuids.projectIdE
        testResults[2].locationId() == uuids.locE1

        // 4th: Project C (via Location C1 @ ~15km)
        testResults[3].projectId() == uuids.projectIdC
        testResults[3].locationId() == uuids.locC1

    }

    @Unroll
    def "SEARCH BY LOCATION | should handle project status #status and shift active state #shiftActive"() {
        given: "seed the database with test organization, project statuses, and shift time configurations"
        def uuids = [:].withDefault { UUID.randomUUID() }

        // SQL Templates Helper
        String idName = "id, name"
        def insertInto = { String table, String columns, String values ->
            "INSERT INTO ${table} (${columns}) VALUES (${values})"
        }

        String insertOrganizationSql = insertInto("organizations", "${idName}, is_public", "?, 'Status Test Org', true")
        String insertProjectSql = insertInto("projects", "id, organization_id, title, description, project_type, status, created_at", "?, ?, ?, 'Description', 'STANDARD', ?, NOW()")
        String insertLocationSql = insertInto("locations", "${idName}, address_line, city, country_code, geom", "?, ?, ?, ?, ?, ST_GeographyFromText(?)")
        String insertProjectLocationSql = insertInto("project_locations", "project_id, location_id", "?, ?")

        String startOffset = shiftActive ? "1 day" : "-1 day"
        String endOffset = shiftActive ? "1 day 2 hours" : "-22 hours"
        String insertShiftA1Sql = insertInto("shifts", "id, project_id, is_virtual, location_id, start_time, end_time", "?, ?, false, ?, NOW() + INTERVAL '${startOffset}', NOW() + INTERVAL '${endOffset}'")
        String insertShiftSql = insertInto("shifts", "id, project_id, is_virtual, location_id, start_time, end_time", "?, ?, false, ?, NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 2 hours'")

        [
                [insertOrganizationSql, [uuids.organizationId]],

                // Project A (multi-location) with status from where block
                [insertProjectSql, [uuids.projectIdA, uuids.organizationId, 'Project A', status]],
                // Project B (single-location) is always ACTIVE
                [insertProjectSql, [uuids.projectIdB, uuids.organizationId, 'Project B', 'ACTIVE']],

                // Locations (Reference point: POINT(-104.9903 39.7392))
                [insertLocationSql, [uuids.locA1, 'Location A1', '123 Closest St', 'Denver', 'US', 'POINT(-104.9903 39.7572)']], // ~2 km (closest)
                [insertLocationSql, [uuids.locB1, 'Location B1', '456 Second St', 'Denver', 'US', 'POINT(-104.9903 39.7842)']], // ~5 km (second closest)
                [insertLocationSql, [uuids.locA2, 'Location A2', '789 Third St', 'Denver', 'US', 'POINT(-104.9903 39.8292)']],  // ~10 km (third closest)

                // Mappings
                [insertProjectLocationSql, [uuids.projectIdA, uuids.locA1]],
                [insertProjectLocationSql, [uuids.projectIdA, uuids.locA2]],
                [insertProjectLocationSql, [uuids.projectIdB, uuids.locB1]],

                // Shifts
                [insertShiftA1Sql, [uuids.shiftA1, uuids.projectIdA, uuids.locA1]],
                [insertShiftSql, [uuids.shiftA2, uuids.projectIdA, uuids.locA2]],
                [insertShiftSql, [uuids.shiftB1, uuids.projectIdB, uuids.locB1]]
        ].each { List<Object> seedStatement -> executeUpdate(seedStatement[0] as String, *(seedStatement[1] as List)) }

        and: "a reference point at Denver center"
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)
        Point point = geometryFactory.createPoint(new Coordinate(-104.9903, 39.7392))

        when: "searching projects by location"
        Page<ProjectSearchCard> page = projectController.searchByLocation(point.getX(), point.getY(), 20000.0, Pageable.from(0, 100))

        then: "the result matches expected order and presence based on project status and shift validity"
        page != null
        List<ProjectSearchCard> testResults = page.content.findAll { it.projectId() in [uuids.projectIdA, uuids.projectIdB] }

        List<UUID> expectedUUIDs = expectedOrderNames.collect { name ->
            name == 'A' ? uuids.projectIdA : uuids.projectIdB
        }
        testResults.collect { it.projectId() } == expectedUUIDs

        where:
        status     | shiftActive | expectedOrderNames
        'ACTIVE'   | true        | ['A', 'B']
        'ACTIVE'   | false       | ['B', 'A']
        'DRAFT'    | true        | ['B']
        'PENDING'  | true        | ['B']
        'FLAGGED'  | true        | ['B']
        'REJECTED' | true        | ['B']
    }

    /********** APPROVE Tests **********/

    def "APPROVE | should allow GLOBAL_ADMIN to approve a virtual project"() {
        given: "an organization and a pending virtual project (no locations)"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Approve Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Virtual Approve Project', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)

        and: "a GLOBAL_ADMIN user"
        def adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-appr-${UUID.randomUUID()}@example.com".toString())

        when: "the GLOBAL_ADMIN approves the project"
        Project approved = projectController.approveProject(projId, createPrincipal(adminId))

        then: "the project transitions to ACTIVE"
        approved.id() == projId
        approved.status() == ACTIVE

        and: "the status is persisted in the database"
        def inDb = sql.firstRow("SELECT status FROM projects WHERE id = ?", [projId])
        inDb.status == 'ACTIVE'
    }

    def "APPROVE | should allow REGION_DIRECTOR to approve a virtual project when org is in their region"() {
        given: "a region, organization in that region, and pending virtual project"
        def regId = UUID.randomUUID()
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Approve Region', ST_GeogFromText('POLYGON((-105.1 39.7, -104.7 39.7, -104.7 39.6, -105.1 39.6, -105.1 39.7))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Director Org', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgId, regId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Director Virtual Project', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)

        and: "a REGION_DIRECTOR of that region"
        def directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, "dir-appr-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, regId)

        when: "the REGION_DIRECTOR approves the virtual project"
        Project approved = projectController.approveProject(projId, createPrincipal(directorId))

        then: "the project transitions to ACTIVE"
        approved.id() == projId
        approved.status() == ACTIVE
    }

    def "APPROVE | should reject REGION_AGENT approval of virtual project with 403 Forbidden"() {
        given: "an organization and pending virtual project"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Agent VOrg', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Agent VProj', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)

        and: "a REGION_AGENT user"
        def agentId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'REGION_AGENT')", agentId, "agent-appr-${UUID.randomUUID()}@example.com".toString())

        when: "the REGION_AGENT attempts to approve the virtual project"
        projectController.approveProject(projId, createPrincipal(agentId))

        then: "a 403 Forbidden is thrown"
        HttpStatusException e = thrown()
        e.status == HttpStatus.FORBIDDEN
    }

    def "APPROVE | should reject approval of non-existent project with 404 Not Found"() {
        given: "a random project ID and an admin user"
        def adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-404-${UUID.randomUUID()}@example.com".toString())

        when: "approving a non-existent project"
        projectController.approveProject(UUID.randomUUID(), createPrincipal(adminId))

        then: "a 404 Not Found is thrown"
        HttpStatusException e = thrown()
        e.status == HttpStatus.NOT_FOUND
    }

    def "APPROVE | should reject approval of already ACTIVE project with 400 Bad Request"() {
        given: "an already ACTIVE project"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Active Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Already Active', 'Desc', 'STANDARD', 'ACTIVE', NOW())
        """, projId, orgId)

        and: "an admin user"
        def adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-active-${UUID.randomUUID()}@example.com".toString())

        when: "attempting to approve an ACTIVE project"
        projectController.approveProject(projId, createPrincipal(adminId))

        then: "a 400 Bad Request is thrown"
        HttpStatusException e = thrown()
        e.status == HttpStatus.BAD_REQUEST
    }

    def "APPROVE | should allow approving a PENDING_UPDATE project"() {
        given: "a project in PENDING_UPDATE status"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'PendingUpdate Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Pending Update Proj', 'Desc', 'STANDARD', 'PENDING_UPDATE', NOW())
        """, projId, orgId)

        and: "an admin user"
        def adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-pu-${UUID.randomUUID()}@example.com".toString())

        when: "approving the PENDING_UPDATE project"
        Project approved = projectController.approveProject(projId, createPrincipal(adminId))

        then: "the project transitions to ACTIVE"
        approved.status() == ACTIVE
    }

    def "APPROVE | should reject approval of soft-deleted project with 404 Not Found"() {
        given: "a soft-deleted pending project"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Deleted Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at, deleted_at)
            VALUES (?, ?, 'Deleted Proj', 'Desc', 'STANDARD', 'PENDING', NOW(), NOW())
        """, projId, orgId)

        and: "an admin user"
        def adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-del-${UUID.randomUUID()}@example.com".toString())

        when: "attempting to approve a deleted project"
        projectController.approveProject(projId, createPrincipal(adminId))

        then: "a 404 Not Found is thrown preventing resurrection"
        HttpStatusException e = thrown()
        e.status == HttpStatus.NOT_FOUND
    }

    def "APPROVE | should write audit log record on approval"() {
        given: "a pending project and an admin"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Audit Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Audit Proj', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)

        and: "an admin user"
        def adminId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'GLOBAL_ADMIN')", adminId, "admin-log-${UUID.randomUUID()}@example.com".toString())

        when: "approving the project"
        projectController.approveProject(projId, createPrincipal(adminId))

        then: "a project_audit_logs record is written"
        def auditRow = sql.firstRow("SELECT action, actor_id FROM project_audit_logs WHERE project_id = ?", [projId])
        auditRow != null
        auditRow.action == 'APPROVED'
        auditRow.actor_id == adminId
    }

    def "APPROVE | should allow REGION_DIRECTOR to approve physical project with locations inside their region"() {
        given: "a region, organization, location inside that region, and physical project"
        def regId = UUID.randomUUID()
        def orgId = UUID.randomUUID()
        def locId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Director Phys Region', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Phys Org', true)", orgId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Phys Loc', 'St', 'Denver', 'US', ST_GeographyFromText('POINT(-104.9903 39.7392)'))", locId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Phys Project', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)
        executeUpdate("INSERT INTO project_locations (project_id, location_id) VALUES (?, ?)", projId, locId)

        and: "a REGION_DIRECTOR assigned to that region"
        def directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, "dir-phys-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, regId)

        when: "the REGION_DIRECTOR approves the physical project"
        Project approved = projectController.approveProject(projId, createPrincipal(directorId))

        then: "the physical project transitions to ACTIVE"
        approved.id() == projId
        approved.status() == ACTIVE
    }

    def "APPROVE | should allow REGION_DIRECTOR to approve virtual project assigned via managing_region_id"() {
        given: "a region, verified organization, and virtual project explicitly assigned via managing_region_id"
        def regId = UUID.randomUUID()
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Director Managing Reg', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Managing Reg Org', true, 'VERIFIED')", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, managing_region_id, title, description, project_type, status, created_at)
            VALUES (?, ?, ?, 'Virtual Assigned Project', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId, regId)

        and: "a REGION_DIRECTOR assigned to that managing region"
        def directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, "dir-mgr-appr-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, regId)

        when: "the REGION_DIRECTOR approves the virtual project"
        Project approved = projectController.approveProject(projId, createPrincipal(directorId))

        then: "the project transitions to ACTIVE"
        approved.id() == projId
        approved.status() == ACTIVE
    }

    def "SUBMIT | should throw 400 when managingRegion does not exist"() {
        given: "an organization and a project referencing a non-existent managing region"
        def org = organizationRepository.save(new Organization(null, "Org For NonExistent Region", null, null, true, UNVERIFIED, null, []))
        def nonExistentRegion = new AdministrativeRegion(UUID.randomUUID(), "Ghost Region", null, null)
        def project = new Project(null, org, nonExistentRegion, "Ghost Project", "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])

        when: "submitting the project"
        projectController.submitProject(project, testPrincipal)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        e.status == BAD_REQUEST
        e.message == "Managing region does not exist"
    }

    def "SUBMIT | should throw 400 when project locations do not fall within managingRegion"() {
        given: "a managing region with a defined boundary"
        def regId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Strict Region', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        def region = new AdministrativeRegion(regId, "Strict Region", null, null)

        and: "an organization with global admin submitting"
        def org = organizationRepository.save(new Organization(null, "Org For Boundary Test", null, null, true, UNVERIFIED, null, []))

        and: "a location outside the managing region"
        def gf = new GeometryFactory(new PrecisionModel(), 4326)
        def outLoc = new Location(null, "Out Loc", "123 St", "Boulder", null, null, "US", gf.createPoint(new Coordinate(-106.0, 41.0)))
        def project = new Project(null, org, region, "Out Project", "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [outLoc], [])

        when: "submitting the project"
        projectController.submitProject(project, testPrincipal)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        e.status == BAD_REQUEST
        e.message == "Project locations do not fall within the specified managing region"
    }

    def "SUBMIT | should throw 403 when submitter lacks authority to assign managingRegion"() {
        given: "a managing region"
        def regId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Auth Region', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        def region = new AdministrativeRegion(regId, "Auth Region", null, null)

        and: "an organization and an unauthorized standard user"
        def org = organizationRepository.save(new Organization(null, "Org For Auth Test", null, null, true, UNVERIFIED, null, []))
        def unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unauth-${UUID.randomUUID()}@example.com".toString())

        def project = new Project(null, org, region, "Unauthorized Region Project", "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])

        when: "submitting the project"
        projectController.submitProject(project, createPrincipal(unauthUserId))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status == FORBIDDEN
        e.message == "You do not have authority to assign this managing region"
    }

    def "UPDATE | should throw 400 when updating project locations outside existing managingRegion"() {
        given: "a managing region and an active project inside it"
        def regId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Update Region', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        def region = new AdministrativeRegion(regId, "Update Region", null, null)
        def org = organizationRepository.save(new Organization(null, "Org For Update Loc Region", null, null, true, UNVERIFIED, null, []))

        def gf = new GeometryFactory(new PrecisionModel(), 4326)
        def inLoc = new Location(null, "In Loc", "123 St", "Denver", null, null, "US", gf.createPoint(new Coordinate(-104.9, 39.7)))
        def initialProject = new Project(null, org, region, "Initial Project", "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [inLoc], [])
        Project saved = projectController.submitProject(initialProject, testPrincipal)

        when: "updating the project with locations outside the managing region without modifying managingRegion"
        def outLoc = new Location(null, "Out Loc", "123 St", "Boulder", null, null, "US", gf.createPoint(new Coordinate(-106.0, 41.0)))
        def updatedProject = new Project(saved.id(), org, region, "Initial Project", "Desc", STANDARD, saved.status(), saved.createdAt(), null, null, [outLoc], [])
        projectController.updateProject(saved.id(), updatedProject, testPrincipal)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        e.status == BAD_REQUEST
        e.message == "Project locations do not fall within the specified managing region"
    }

    @Unroll
    def "SEARCH | should reject invalid coordinates: lon=#lon, lat=#lat, rad=#rad"(double lon, double lat, double rad) {
        when: "searching projects by location with invalid coordinates or radius"
        projectController.searchByLocation(lon, lat, rad, Pageable.unpaged())

        then: "a validation exception is thrown"
        thrown(ValidationException)

        where:
        lon    | lat   | rad
        -190.0 | 0.0   | 1000.0
        195.0  | 0.0   | 1000.0
        0.0    | -95.0 | 1000.0
        0.0    | 95.0  | 1000.0
        0.0    | 0.0   | -1.0
        0.0    | 0.0   | 600000.0
    }

    def "SUBMIT | should throw 400 when managingRegion payload has null ID"() {
        given: "a project with managingRegion that has null ID"
        def org = getRandomOrganization()
        def regionWithNullId = new AdministrativeRegion(null, "Null Id Region", null, null)
        def project = new Project(null, org, regionWithNullId, "Test Title", "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])

        when: "submitting the project"
        projectController.submitProject(project, testPrincipal)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        e.status == BAD_REQUEST
        e.message == "Managing region does not exist"
    }

    def "SUBMIT | should successfully save virtual project with valid managingRegion"() {
        given: "a valid region and organization where user has authority"
        UUID regId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Submit Virtual Region', ST_GeogFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))
        """, regId)
        def org = organizationRepository.save(new Organization(null, "Org For Virtual Region", null, null, true, UNVERIFIED, null, []))
        def region = new AdministrativeRegion(regId, "Submit Virtual Region", null, null)
        def project = new Project(null, org, region, "Virtual Region Proj", "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])

        when: "submitting virtual project with global admin"
        Project saved = projectController.submitProject(project, testPrincipal)

        then: "project is saved and managingRegion is set"
        saved.id() != null
        saved.managingRegion() != null
        saved.managingRegion().id() == regId
    }

    def "UPDATE | should throw 404 when reassigned organization has null ID"() {
        given: "an existing project"
        def org = getRandomOrganization()
        def project = TestFixtures.createBasicProject(org, "Org Reassign Null ID", "Desc", STANDARD, DRAFT)
        Project saved = projectController.submitProject(project, testPrincipal)

        when: "updating the project with an organization shell having a null ID"
        def orgWithNullId = new Organization(null, "Shell Org", null, null, true, UNVERIFIED, null, [])
        def updatedProject = new Project(saved.id(), orgWithNullId, null, saved.title(), saved.description(), saved.projectType(), saved.status(), saved.createdAt(), null, null, [], [])
        projectController.updateProject(saved.id(), updatedProject, testPrincipal)

        then: "a 404 Not Found is thrown"
        def e = thrown(HttpStatusException)
        e.status == NOT_FOUND
        e.message == "Target organization not found"
    }

    def "UPDATE | should throw 400 when updating managingRegion with null ID"() {
        given: "an existing project with no managingRegion"
        def org = getRandomOrganization()
        def project = TestFixtures.createBasicProject(org, "Update Region Null ID", "Desc", STANDARD, DRAFT)
        Project saved = projectController.submitProject(project, testPrincipal)

        when: "updating with a managingRegion whose ID is null"
        def regionWithNullId = new AdministrativeRegion(null, "Null ID Region", null, null)
        def updatedProject = new Project(saved.id(), org, regionWithNullId, saved.title(), saved.description(), saved.projectType(), saved.status(), saved.createdAt(), null, null, [], [])
        projectController.updateProject(saved.id(), updatedProject, testPrincipal)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        e.status == BAD_REQUEST
        e.message == "Managing region does not exist"
    }

    def "UPDATE | should throw 403 when user lacks authority to assign new managingRegion on update"() {
        given: "an organization with a manager and a separate foreign region"
        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Update Region Org', true, 'VERIFIED')", orgId)

        def foreignRegionId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Foreign Region', ST_GeogFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))
        """, foreignRegionId)

        def managerId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", managerId, "mgr-unauth-reg-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", managerId, orgId)

        def org = new Organization(orgId, "Update Region Org", null, null, true, UNVERIFIED, null, [])
        def initialProject = TestFixtures.createBasicProject(org, "Unauth Region Proj", "Desc", STANDARD, DRAFT)
        Project saved = projectController.submitProject(initialProject, testPrincipal)

        when: "the Org Manager attempts to assign a foreign region not linked to their organization"
        def foreignRegion = new AdministrativeRegion(foreignRegionId, "Foreign Region", null, null)
        def updateRequest = new Project(saved.id(), org, foreignRegion, saved.title(), saved.description(), saved.projectType(), saved.status(), saved.createdAt(), null, null, [], [])
        projectController.updateProject(saved.id(), updateRequest, createPrincipal(managerId))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status == FORBIDDEN
        e.message == "You do not have authority to assign this managing region"
    }

    def "UPDATE | should throw 403 when unauthorized user attempts to unassign managingRegion"() {
        given: "a project with a managingRegion in Region B under an organization not bound to Region B"
        def regBId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Region B', ST_GeogFromText('POLYGON((-104.8 39.7, -104.6 39.7, -104.6 39.8, -104.8 39.8, -104.8 39.7))'))
        """, regBId)

        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org Unassign Region', true, 'VERIFIED')", orgId)

        def managerId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", managerId, "mgr-unauth-unassign-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", managerId, orgId)

        def org = new Organization(orgId, "Org Unassign Region", null, null, true, UNVERIFIED, null, [])
        def regB = new AdministrativeRegion(regBId, "Region B", null, null)
        def initialProject = new Project(null, org, regB, "Project with Region B", "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])
        Project saved = projectController.submitProject(initialProject, testPrincipal)

        when: "the Org Manager attempts to unassign the managingRegion"
        def updateRequest = new Project(saved.id(), org, null, saved.title(), saved.description(), saved.projectType(), saved.status(), saved.createdAt(), null, null, [], [])
        projectController.updateProject(saved.id(), updateRequest, createPrincipal(managerId))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status == FORBIDDEN
        e.message == "You do not have authority to unassign this managing region"
    }

    def "UPDATE | should allow authorized Org Manager to unassign managingRegion"() {
        given: "a region and an organization bounded to it with an Org Manager"
        def regId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Region For Unassign', ST_GeogFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))
        """, regId)

        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org For Unassign', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgId, regId)

        def managerId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", managerId, "mgr-unassign-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", managerId, orgId)

        def org = new Organization(orgId, "Org For Unassign", null, null, true, UNVERIFIED, null, [])
        def region = new AdministrativeRegion(regId, "Region For Unassign", null, null)
        def initialProject = new Project(null, org, region, "Project with Region", "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])
        Project saved = projectController.submitProject(initialProject, testPrincipal)

        when: "the Org Manager updates the project setting managingRegion to null"
        def updateRequest = new Project(saved.id(), org, null, saved.title(), saved.description(), saved.projectType(), saved.status(), saved.createdAt(), null, null, [], [])
        Project updated = projectController.updateProject(saved.id(), updateRequest, createPrincipal(managerId))

        then: "managingRegion is successfully unassigned"
        updated.managingRegion() == null
        def dbRow = sql.firstRow("SELECT managing_region_id FROM projects WHERE id = ?", [saved.id()])
        dbRow.managing_region_id == null
    }

    def "UPDATE | should demote ACTIVE project to PENDING when reassigned to organization outside geographic bounds"() {
        given: "two regions: Denver and Boulder"
        def denverRegId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Denver City Reassign', ST_GeogFromText('POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))'))
        """, denverRegId)

        def boulderRegId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Boulder City Reassign', ST_GeogFromText('POLYGON((-105.35 39.95, -105.15 39.95, -105.15 40.10, -105.35 40.10, -105.35 39.95))'))
        """, boulderRegId)

        def orgAId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Denver Reassign Org A', true, 'VERIFIED')", orgAId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgAId, denverRegId)

        def orgBId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Boulder Reassign Org B', true, 'VERIFIED')", orgBId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgBId, boulderRegId)

        and: "an ACTIVE project under Denver Org with a location in Denver"
        def gf = new GeometryFactory(new PrecisionModel(), 4326)
        def denverPoint = gf.createPoint(new Coordinate(-104.9, 39.7))
        def denverLoc = new Location(UUID.randomUUID(), "Denver Reassign Loc", "16th St", "Denver", "CO", "80202", "US", denverPoint)

        def projId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Active Denver Reassign Project', 'Desc', 'STANDARD', 'ACTIVE', NOW())
        """, projId, orgAId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Denver Reassign Loc', '16th St', 'Denver', 'US', ST_GeogFromText('POINT(-104.9 39.7)'))",
                denverLoc.id())
        executeUpdate("INSERT INTO project_locations (project_id, location_id) VALUES (?, ?)", projId, denverLoc.id())

        def orgB = new Organization(orgBId, "Boulder Reassign Org B", null, null, true, UNVERIFIED, null, [])
        def updateRequest = new Project(projId, orgB, null, "Active Denver Reassign Project", "Desc", STANDARD, ACTIVE, OffsetDateTime.now(), null, null, [denverLoc], [])

        when: "a Global Admin reassigns the active Denver project to Boulder Org"
        Project updated = projectController.updateProject(projId, updateRequest, testPrincipal)

        then: "the status is automatically demoted to PENDING for regional review"
        updated.status() == PENDING
        def row = sql.firstRow("SELECT status, organization_id FROM projects WHERE id = ?", [projId])
        row.status == 'PENDING'
        row.organization_id == orgBId
    }

    def "UPDATE | should preserve ACTIVE status when reassigned to organization containing geographic bounds"() {
        given: "a shared regional boundary enclosing Denver"
        def regionId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Shared Denver Region', ST_GeogFromText('POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))'))
        """, regionId)

        def org1Id = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org Alpha Shared', true, 'VERIFIED')", org1Id)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org1Id, regionId)

        def org2Id = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org Beta Shared', true, 'VERIFIED')", org2Id)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org2Id, regionId)

        and: "an ACTIVE project in Org Alpha"
        def gf = new GeometryFactory(new PrecisionModel(), 4326)
        def denverPoint = gf.createPoint(new Coordinate(-104.9, 39.7))
        def denverLoc = new Location(UUID.randomUUID(), "Denver Shared Loc", "16th St", "Denver", "CO", "80202", "US", denverPoint)

        def projId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Active Alpha Shared Proj', 'Desc', 'STANDARD', 'ACTIVE', NOW())
        """, projId, org1Id)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Denver Shared Loc', '16th St', 'Denver', 'US', ST_GeogFromText('POINT(-104.9 39.7)'))",
                denverLoc.id())
        executeUpdate("INSERT INTO project_locations (project_id, location_id) VALUES (?, ?)", projId, denverLoc.id())

        def org2 = new Organization(org2Id, "Org Beta Shared", null, null, true, UNVERIFIED, null, [])
        def updateRequest = new Project(projId, org2, null, "Active Alpha Shared Proj", "Desc", STANDARD, ACTIVE, OffsetDateTime.now(), null, null, [denverLoc], [])

        when: "reassigning to Org Beta which also encloses the location"
        Project updated = projectController.updateProject(projId, updateRequest, testPrincipal)

        then: "status is preserved as ACTIVE"
        updated.status() == ACTIVE
        def row = sql.firstRow("SELECT status, organization_id FROM projects WHERE id = ?", [projId])
        row.status == 'ACTIVE'
        row.organization_id == org2Id
    }

    def "UPDATE | should reject project update by unaffiliated standard user with 403 Forbidden"() {
        given: "an existing project and an unaffiliated standard user"
        def org = getRandomOrganization()
        def project = TestFixtures.createBasicProject(org, "Protected Project", "Desc", STANDARD, DRAFT)
        Project saved = projectController.submitProject(project, testPrincipal)

        def unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unaffil-update-${UUID.randomUUID()}@example.com".toString())

        when: "unaffiliated user attempts to update the project"
        def updateRequest = new Project(saved.id(), org, null, "Tampered Title", "Desc", STANDARD, DRAFT, saved.createdAt(), null, null, [], [])
        projectController.updateProject(saved.id(), updateRequest, createPrincipal(unauthUserId))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status == FORBIDDEN
        e.message == "You do not have permission to modify this project"
    }

    def "DELETE | should reject project deletion by unaffiliated user with 403 Forbidden"() {
        given: "an existing project and an unaffiliated standard user"
        def org = getRandomOrganization()
        def project = TestFixtures.createBasicProject(org, "Protected Delete Proj", "Desc", STANDARD, DRAFT)
        Project saved = projectController.submitProject(project, testPrincipal)

        def unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unaffil-del-${UUID.randomUUID()}@example.com".toString())

        when: "unaffiliated user attempts to delete the project"
        projectController.deleteProject(saved.id(), createPrincipal(unauthUserId))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status == FORBIDDEN
        e.message == "You do not have permission to delete this project"
    }

    def "DELETE | should allow Org Manager to delete project belonging to their organization"() {
        given: "an organization with an Org Manager and a project"
        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Delete Test Org', true, 'VERIFIED')", orgId)

        def managerId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", managerId, "mgr-del-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", managerId, orgId)

        def org = new Organization(orgId, "Delete Test Org", null, null, true, UNVERIFIED, null, [])
        def project = TestFixtures.createBasicProject(org, "Project to Delete", "Desc", STANDARD, DRAFT)
        Project saved = projectController.submitProject(project, testPrincipal)

        when: "the Org Manager deletes the project"
        projectController.deleteProject(saved.id(), createPrincipal(managerId))

        then: "the project is deleted from the database"
        sql.firstRow("SELECT id FROM projects WHERE id = ?", [saved.id()]) == null
    }

    def "APPROVE | should reject approval of DRAFT project with 400 Bad Request"() {
        given: "a project in DRAFT status"
        def org = getRandomOrganization()
        def project = TestFixtures.createBasicProject(org, "Draft Approval Project", "Desc", STANDARD, DRAFT)
        Project saved = projectController.submitProject(project, testPrincipal)

        when: "attempting to approve the draft project"
        projectController.approveProject(saved.id(), testPrincipal)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        e.status == BAD_REQUEST
        e.message == "Only PENDING or PENDING_UPDATE projects can be approved"
    }

    def "APPROVE | should reject approval when REGION_DIRECTOR lacks geographic jurisdiction over physical locations"() {
        given: "a Denver region and a Region Director for Denver"
        def denverRegId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Denver Approval Region', ST_GeogFromText('POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))'))
        """, denverRegId)

        def directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, "dir-denver-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, denverRegId)

        and: "a PENDING project with physical locations in Colorado Springs (outside Denver)"
        def org = getRandomOrganization()
        def gf = new GeometryFactory(new PrecisionModel(), 4326)
        def csPoint = gf.createPoint(new Coordinate(-104.82, 38.83))
        def csLoc = new Location(UUID.randomUUID(), "Colorado Springs Loc", "123 Main", "Colo Springs", "CO", "80903", "US", csPoint)

        def projId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'CS Project', 'Desc', 'STANDARD', 'PENDING', NOW())
        """, projId, org.id())
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'CS Loc', '123 Main', 'Colo Springs', 'US', ST_GeogFromText('POINT(-104.82 38.83)'))",
                csLoc.id())
        executeUpdate("INSERT INTO project_locations (project_id, location_id) VALUES (?, ?)", projId, csLoc.id())

        when: "the Denver Region Director attempts to approve the project"
        projectController.approveProject(projId, createPrincipal(directorId))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpStatusException)
        e.status == FORBIDDEN
        e.message == "You do not have geographic jurisdiction to approve this project."
    }

    def "APPROVE | should throw 404 Not Found when approving principal does not exist in users table"() {
        given: "a PENDING project"
        def org = getRandomOrganization()
        def project = TestFixtures.createBasicProject(org, "Actor 404 Project", "Desc", STANDARD, PENDING)
        Project saved = projectController.submitProject(project, testPrincipal)

        when: "calling approveProject with a principal UUID that is not in the users table"
        def nonExistentUserPrincipal = createPrincipal(UUID.randomUUID())
        projectController.approveProject(saved.id(), nonExistentUserPrincipal)

        then: "a 404 Not Found is thrown"
        def e = thrown(HttpStatusException)
        e.status == NOT_FOUND
        e.message == "User not found"
    }

    def "SEARCH BY LOCATION | should return empty page when no projects exist within search radius"() {
        when: "searching in the middle of the Atlantic Ocean where zero projects exist"
        def emptyPage = projectController.searchByLocation(0.0, 0.0, 1000.0, Pageable.unpaged())

        then: "an empty Page is returned"
        emptyPage != null
        emptyPage.content.isEmpty()
        emptyPage.totalSize == 0
    }
}

