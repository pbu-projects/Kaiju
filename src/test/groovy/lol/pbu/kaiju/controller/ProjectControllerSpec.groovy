package lol.pbu.kaiju.controller

import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.TestFixtures
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.model.ProjectSearchCard
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.security.ProjectSecurityService
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Unroll

import java.security.Principal

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
        def controller = new ProjectController(projectRepository, realProjectSecurityService, organizationRepository)

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
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Director Org', true)", orgId)
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
}
