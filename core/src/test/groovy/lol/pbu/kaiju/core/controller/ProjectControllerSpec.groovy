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
        new Organization(orgRow.id as UUID, orgRow.name as String, null, null, [])
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid project"() {
        given: "a new valid project"
        def org = getRandomOrganization()
        def newProject = new Project(null, org, "Test Project ${faker.company().name()}", "Test Description ${faker.lorem().paragraph()}", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])

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
            def dummyOrg = new Organization(UUID.randomUUID(), "Dummy Org", null, null, [])

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
                def proj = new Project(null, props.organization as Organization, props.title as String, props.description as String, props.projectType as ProjectType, props.status as ProjectStatus, OffsetDateTime.now(), null, null, [], [])
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
        def updateRequest = new Project(null, org, newTitle, newDescription, projectType, ACTIVE, OffsetDateTime.now(), null, null, [], [])

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
        def updateRequest = new Project(null, org, "New Title", "New Description", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])

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
        def updateRequest = new Project(null, org, "New Title", "New Description", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])

        when: "a no-look update is attempted"
        projectController.updateProjectNoLook(nonExistentId, updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing project"() {
        given: "a new project to be deleted"
        def org = getRandomOrganization()
        def tempProject = new Project(null, org, "Temporary Project to Delete", "Temporary Description", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])
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
        given: "seed the database with test organization, project, locations (one close, one far), and active shifts"
        def uuids = [:].withDefault { UUID.randomUUID() }

        // SQL Templates Helper
        String idName = "id, name"
        def insertInto = { String table, String columns, String values ->
            "INSERT INTO ${table} (${columns}) VALUES (${values})"
        }

        String insertOrganizationSql = insertInto("organizations", "${idName}, is_public", "?, 'Distance Test Org', true")
        String insertProjectSql = insertInto("projects", "id, organization_id, title, description, project_type, status, created_at", "?, ?, 'Distance Test Project', 'Description', 'STANDARD', 'ACTIVE', NOW()")
        String insertLocationSql = insertInto("locations", "${idName}, address_line, city, country_code, geom", "?, ?, ?, ?, ?, ST_GeographyFromText(?)")
        String insertProjectLocationSql = insertInto("project_locations", "project_id, location_id", "?, ?")
        String insertShiftSql = insertInto("shifts", "id, project_id, is_virtual, location_id, start_time, end_time", "?, ?, false, ?, NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 2 hours'")

        [[insertOrganizationSql, [uuids.organizationId]],
         [insertProjectSql, [uuids.projectId, uuids.organizationId]],
         [insertLocationSql, [uuids.closeLocationId, 'Close Location', '123 Close St', 'Denver', 'US', 'POINT(-104.99 39.74)']],
         [insertLocationSql, [uuids.farLocationId, 'Far Location', '456 Far St', 'Denver', 'US', 'POINT(-104.9 39.7)']],
         [insertProjectLocationSql, [uuids.projectId, uuids.closeLocationId]],
         [insertProjectLocationSql, [uuids.projectId, uuids.farLocationId]],
         [insertShiftSql, [uuids.closeShiftId, uuids.projectId, uuids.closeLocationId]],
         [insertShiftSql, [uuids.farShiftId, uuids.projectId, uuids.farLocationId]]].each { List<Object> seedStatement -> executeUpdate(seedStatement[0] as String, *(seedStatement[1] as List)) }

        and: "a reference point at Denver center"
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)
        Point point = geometryFactory.createPoint(new Coordinate(-104.9903, 39.7392))

        when: "searching projects by location with 20km radius"
        Page<ProjectSearchCard> page = projectRepository.searchByLocation(point, 20000.0, Pageable.from(0, 10))

        then: "only our distance test project-locations are retrieved (or existing matching ones), ordered closest first"
        page != null
        List<ProjectSearchCard> testResults = page.content.findAll { it.projectId() == uuids.projectId }
        testResults.size() == 2
        testResults[0].locationId() == uuids.closeLocationId
        testResults[0].locationName() == 'Close Location'
        testResults[1].locationId() == uuids.farLocationId
        testResults[1].locationName() == 'Far Location'

        cleanup:
        if (uuids.containsKey('closeShiftId')) {
            standaloneConnection.createStatement().execute("DELETE FROM shifts WHERE id IN ('${uuids.closeShiftId}', '${uuids.farShiftId}')")
        }
        if (uuids.containsKey('projectId')) {
            standaloneConnection.createStatement().execute("DELETE FROM project_locations WHERE project_id = '${uuids.projectId}'")
            standaloneConnection.createStatement().execute("DELETE FROM projects WHERE id = '${uuids.projectId}'")
        }
        if (uuids.containsKey('closeLocationId')) {
            standaloneConnection.createStatement().execute("DELETE FROM locations WHERE id IN ('${uuids.closeLocationId}', '${uuids.farLocationId}')")
        }
        if (uuids.containsKey('organizationId')) {
            standaloneConnection.createStatement().execute("DELETE FROM organizations WHERE id = '${uuids.organizationId}'")
        }
    }
}
