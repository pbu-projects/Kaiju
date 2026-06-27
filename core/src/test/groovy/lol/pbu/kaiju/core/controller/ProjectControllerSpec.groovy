package lol.pbu.kaiju.core.controller

import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.core.domain.Organization
import lol.pbu.kaiju.core.domain.Project
import lol.pbu.kaiju.core.model.ProjectSearchCard
import lol.pbu.kaiju.core.model.ProjectStatus
import lol.pbu.kaiju.core.model.ProjectType
import lol.pbu.kaiju.core.model.VerificationStatus
import lol.pbu.kaiju.core.repository.ProjectRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Unroll

import java.time.OffsetDateTime

import static lol.pbu.kaiju.core.model.ProjectStatus.ACTIVE
import static lol.pbu.kaiju.core.model.ProjectStatus.DRAFT
import static lol.pbu.kaiju.core.model.ProjectType.STANDARD

class ProjectControllerSpec extends BaseControllerSpec {

    @Inject
    ProjectRepository projectRepository

    @Inject
    ProjectController projectController

    @Shared
    Faker faker = new Faker()

    private Organization getRandomOrganization() {
        def orgRow = sql.firstRow("SELECT id, name FROM organizations LIMIT 1")
        if (!orgRow) {
            throw new IllegalStateException("No organizations found in database to link project to.")
        }
        new Organization(orgRow.id as UUID, orgRow.name as String, null, null, true, VerificationStatus.UNVERIFIED, null, [])
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid project"() {
        given: "a new valid project"
        def org = getRandomOrganization()
        def newProject = new Project(null, org, null, "Test Project ${faker.company().name()}", "Test Description ${faker.lorem().paragraph()}", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])

        when: "the project is added"
        Project saved = projectController.addProject(newProject)

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
    def "CREATE | should fail to save project with invalid data: #testCase"(String testCase, Project project) {
        when: "an attempt is made to add a project with invalid data"
        projectController.addProject(project)

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, project] << {
            def dummyOrg = new Organization(UUID.randomUUID(), "Dummy Org", null, null, true, VerificationStatus.UNVERIFIED, null, [])

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
                def proj = new Project(null, props.organization as Organization, null, props.title as String, props.description as String, props.projectType as ProjectType, props.status as ProjectStatus, OffsetDateTime.now(), null, null, [], [])
                [invalidCase.caseName, proj]
            }
        }()
    }

    /********** READ Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "READ | should retrieve an existing project by ID: #title"(UUID id, String title) {
        when: "the project is requested by its ID"
        def result = projectController.getProject(id)

        then: "the correct project is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
            result.get().title() == title
        }

        where:
        [id, title] << sql.rows("SELECT id, title FROM projects LIMIT 3").collect { [it.id, it.title] }
    }

    def "READ | should return empty for a non-existent project ID"() {
        when: "a non-existent project is requested"
        def result = projectController.getProject(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    def "READ | should retrieve projects by title"() {
        given: "an existing project's title from the database"
        def existingProject = sql.firstRow("SELECT title FROM projects LIMIT 1")
        assert existingProject != null

        when: "projects are searched by this title"
        def page = projectController.getProjects(existingProject.title as String, CursoredPageable.from(10, Sort.of(Sort.Order.asc("title"))))

        then: "the search returns a page containing the project"
        verifyAll {
            page != null
            page.content.any { it.title() == existingProject.title }
        }
    }

    /********** UPDATE Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "UPDATE | should successfully update an existing project: #originalTitle"(UUID id, String originalTitle) {
        given: "an existing project's details"
        def projectRow = sql.firstRow("SELECT * FROM projects WHERE id = ?", [id])
        def org = getRandomOrganization()
        def newTitle = "Updated ${faker.book().title()}"
        def newDescription = "Updated Description ${faker.lorem().paragraph()}"
        ProjectType projectType = ProjectType.valueOf(projectRow.project_type as String)
        def updateRequest = new Project(null, org, null, newTitle, newDescription, projectType, ACTIVE, OffsetDateTime.now(), null, null, [], [])

        when: "the project is updated"
        Project updated = projectController.updateProject(id, updateRequest)

        then: "the returned project contains the updated data"
        verifyAll {
            updated.id() == id
            updated.title() == newTitle
            updated.description() == newDescription
            updated.status() == ACTIVE
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT title, description, status FROM projects WHERE id = ?", [id])
        verifyAll(dbResult) {
            title == newTitle
            description == newDescription
            status == 'ACTIVE'
        }

        where:
        [id, originalTitle] << sql.rows("SELECT id, title FROM projects LIMIT 2").collect { [it.id, it.title] }
    }

    def "UPDATE | should fail to update a non-existent project"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def org = getRandomOrganization()
        def updateRequest = new Project(null, org, null, "New Title", "New Description", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])

        when: "an update is attempted"
        projectController.updateProject(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should handle update of non-existent project gracefully when using no-look"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def org = getRandomOrganization()
        def updateRequest = new Project(null, org, null, "New Title", "New Description", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])

        when: "a no-look update is attempted"
        projectController.updateProjectNoLook(nonExistentId, updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing project"() {
        given: "a new project to be deleted"
        def org = getRandomOrganization()
        def tempProject = new Project(null, org, null, "Temporary Project to Delete", "Temporary Description", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])
        def saved = projectController.addProject(tempProject)
        UUID id = saved.id()
        assert projectRepository.existsById(id)

        when: "the project is deleted"
        projectController.deleteProject(id)

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
        projectController.deleteProject(nonExistentId)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "DELETE | should handle deletion of non-existent project gracefully when using no-look"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a no-look delete is attempted"
        projectController.deleteProjectNoLook(nonExistentId)

        then: "no exception is thrown"
        noExceptionThrown()
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
}
