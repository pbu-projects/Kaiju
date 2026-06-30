package lol.pbu.kaiju.core.controller


import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.core.domain.Organization
import lol.pbu.kaiju.core.model.VerificationStatus
import lol.pbu.kaiju.core.repository.OrganizationRepository
import net.datafaker.Faker
import spock.lang.Shared
import spock.lang.Unroll

class OrganizationControllerSpec extends BaseControllerSpec {

    @Inject
    OrganizationRepository organizationRepository

    @Inject
    OrganizationController organizationController

    @Shared
    Faker faker = new Faker()

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid organization"() {
        given: "a new valid organization"
        def newOrg = new Organization(
                null,
                "Test Org ${faker.company().name()}",
                "https://${faker.internet().domainName()}",
                null,
                true,
                VerificationStatus.UNVERIFIED,
                null,
                []
        )

        when: "the organization is added"
        Organization saved = organizationController.addOrganization(newOrg)

        then: "the organization is persisted with a generated ID"
        verifyAll {
            saved.id() != null
            saved.name() == newOrg.name()
            saved.websiteUrl() == newOrg.websiteUrl()
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM organizations WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == name
            saved.websiteUrl() == website_url
        }
    }

    @Unroll
    def "CREATE | should fail to save organization with invalid data: #testCase"(String testCase, Organization organization) {
        when: "an attempt is made to add an organization with invalid data"
        organizationController.addOrganization(organization)

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, organization] << {
            def validData = [
                    name      : "Valid Name",
                    websiteUrl: "https://example.com"
            ]

            def invalidCases = [
                    [field: 'name', value: null, caseName: "Null Name"],
                    [field: 'name', value: ' ', caseName: "Blank Name"],
                    [field: 'name', value: 'A' * 256, caseName: "Name Too Long"],
                    [field: 'websiteUrl', value: '', caseName: "Blank Website URL"],
                    [field: 'websiteUrl', value: 'A' * 256, caseName: "Website URL Too Long"]
            ]

            return invalidCases.collect { invalidCase ->
                def props = new HashMap(validData)
                props[invalidCase.field] = invalidCase.value
                def org = new Organization(
                        null,
                        props.name as String,
                        props.websiteUrl as String,
                        null,
                        true,
                        VerificationStatus.UNVERIFIED,
                        null,
                        []
                )
                [invalidCase.caseName, org]
            }
        }()
    }

    /********** READ Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "READ | should retrieve an existing organization by ID: #name"(UUID id, String name) {
        when: "the organization is requested by its ID"
        def result = organizationController.getOrganization(id)

        then: "the correct organization is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
            result.get().name() == name
        }

        where:
        [id, name] << sql.rows("SELECT id, name FROM organizations LIMIT 3").collect { [it.id, it.name] }
    }

    def "READ | should return empty for a non-existent organization ID"() {
        when: "a non-existent organization is requested"
        def result = organizationController.getOrganization(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "UPDATE | should successfully update an existing organization: #originalName"(UUID id, String originalName) {
        given: "an existing organization's details"
        def newName = "Updated ${faker.company().name()}"
        def newUrl = "https://${faker.internet().domainName()}"
        def updateRequest = new Organization(null, newName, newUrl, null, true, VerificationStatus.UNVERIFIED, null, [])

        when: "the organization is updated"
        Organization updated = organizationController.updateOrganization(id, updateRequest)

        then: "the returned organization contains the updated data"
        verifyAll {
            updated.id() == id
            updated.name() == newName
            updated.websiteUrl() == newUrl
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT name, website_url FROM organizations WHERE id = ?", [id])
        verifyAll(dbResult) {
            name == newName
            website_url == newUrl
        }

        where:
        [id, originalName] << sql.rows("SELECT id, name FROM organizations LIMIT 2").collect { [it.id, it.name] }
    }

    def "UPDATE | should fail to update a non-existent organization"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new Organization(null, "Test Org", "https://example.com", null, true, VerificationStatus.UNVERIFIED, null, [])

        when: "an update is attempted"
        organizationController.updateOrganization(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should handle update of non-existent organization gracefully when using no-look"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new Organization(null, "Test Org", "https://example.com", null, true, VerificationStatus.UNVERIFIED, null, [])

        when: "a no-look update is attempted"
        organizationController.updateOrganizationNoLook(nonExistentId, updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing organization"() {
        given: "a new organization to be deleted"
        def tempOrg = new Organization(
                null,
                "Temporary Org to Delete",
                "https://example.org",
                null,
                true,
                VerificationStatus.UNVERIFIED,
                null,
                []
        )
        def saved = organizationController.addOrganization(tempOrg)
        UUID id = saved.id()
        assert organizationRepository.existsById(id)

        when: "the organization is deleted"
        organizationController.deleteOrganization(id)

        then: "the organization no longer exists in the repository or database"
        verifyAll {
            !organizationRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM organizations WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent organization"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted"
        organizationController.deleteOrganization(nonExistentId)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "DELETE | should handle deletion of non-existent organization gracefully when using no-look"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a no-look delete is attempted"
        organizationController.deleteOrganizationNoLook(nonExistentId)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** LIST Tests **********/

    def "LIST | should fully drain all organizations sequentially using cursors"() {
        setup:
        Set<Organization> allOrgs = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("name")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<Organization> page = organizationController.getOrganizations(pageable)
            allOrgs.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all organizations from the database"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM organizations").count
        verifyAll {
            allOrgs.size() == totalCount
            allOrgs.size() >= 2
        }
    }
}
