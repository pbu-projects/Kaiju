package lol.pbu.kaiju.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.OrganizationRepository
import net.datafaker.Faker
import spock.lang.Shared
import spock.lang.Unroll

import static lol.pbu.kaiju.model.VerificationStatus.*

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
                UNVERIFIED,
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
                        UNVERIFIED,
                        null,
                        []
                )
                [invalidCase.caseName, org]
            }
        }()
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing organization by ID"() {
        given: "an existing organization"
        def org = organizationRepository.save(new Organization(null, "Test Organization Read", "https://example.com", null, true, UNVERIFIED, null, []))
        UUID id = org.id()

        when: "the organization is requested by its ID"
        def result = organizationController.getOrganization(id)

        then: "the correct organization is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
            result.get().name() == "Test Organization Read"
        }
    }

    def "READ | should return empty for a non-existent organization ID"() {
        when: "a non-existent organization is requested"
        def result = organizationController.getOrganization(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing organization"() {
        given: "an existing organization"
        def org = organizationRepository.save(new Organization(null, "Original Org Name", "https://example.com", null, true, UNVERIFIED, null, []))
        UUID id = org.id()
        def newName = "Updated ${faker.company().name()}"
        def newUrl = "https://${faker.internet().domainName()}"
        def updateRequest = new Organization(null, newName, newUrl, null, true, UNVERIFIED, null, [])

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
    }

    def "UPDATE | should fail to update a non-existent organization"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new Organization(null, "Test Org", "https://example.com", null, true, UNVERIFIED, null, [])

        when: "an update is attempted"
        organizationController.updateOrganization(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }



    @Unroll
    def "UPDATE | should prevent mass assignment vulnerabilities: #testCase"(String testCase, Organization updatePayload, boolean shouldNameChange, VerificationStatus expectedStatus) {
        given: "an existing organization"
        def org = organizationRepository.save(new Organization(null, "Original Name", "https://example.com", null, true, UNVERIFIED, null, []))
        UUID id = org.id()

        when: "an update is submitted"
        Organization updated = organizationController.updateOrganization(id, updatePayload)

        then: "the safe fields are updated correctly"
        updated.name() == (shouldNameChange ? updatePayload.name() : "Original Name")

        and: "the sensitive fields are NOT updated"
        updated.verificationStatus() == expectedStatus

        where:
        testCase                           | updatePayload                                                                                                           || shouldNameChange | expectedStatus
        "Change safe field only"           | new Organization(null, "New Name", "https://example.com", null, true, UNVERIFIED, null, [])                             || true             | UNVERIFIED
        "Attempt to escalate verification" | new Organization(null, "Original Name", "https://example.com", null, true, VERIFIED, null, [])                          || false            | UNVERIFIED
        "Change safe and attempt escalate" | new Organization(null, "Hacked Name", "https://example.com", null, true, VERIFIED, null, [])                            || true             | UNVERIFIED
        "Attempt to set REVOKED"          | new Organization(null, "Original Name", "https://example.com", null, true, REVOKED, null, [])                          || false            | UNVERIFIED
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
                UNVERIFIED,
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

    /********** SEARCH Tests **********/

    def "SEARCH BY NAME | exact search with double quotes should return strictly the exact organization"() {
        given: "a cluster of organizations with similar names (Salvation Army variations)"
        def names = [
                "The Salvation Army",
                "The Salvation Army - Denver Citadel Corps",
                "The Salvation Army - Aurora Corps Community Center",
                "The Salvation Army - Colorado Springs Corps",
                "The Salvation Army Intermountain Division",
                "Salvation Army Family Store",
                "Salvation Army Emergency Disaster Services",
                "Friends of The Salvation Army"
        ]
        names.each { orgName ->
            executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, ?, true, 'VERIFIED')", UUID.randomUUID(), orgName)
        }

        when: "searching with double quotes for exact parent name"
        Page<Organization> results = organizationController.searchByName('"The Salvation Army"', false, Pageable.from(0, 10))

        then: "only the exact match is returned"
        results.content.size() == 1
        results.content[0].name == "The Salvation Army"
    }

    def "SEARCH BY NAME | exact search with single quotes should return strictly the exact organization"() {
        given: "a cluster of organizations with similar names"
        def names = [
                "The Salvation Army",
                "The Salvation Army - Denver Citadel Corps",
                "Salvation Army Family Store"
        ]
        names.each { orgName ->
            executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, ?, true, 'VERIFIED')", UUID.randomUUID(), orgName)
        }

        when: "searching with single quotes for exact name"
        Page<Organization> results = organizationController.searchByName("'The Salvation Army'", false, Pageable.from(0, 10))

        then: "only the exact match is returned"
        results.content.size() == 1
        results.content[0].name == "The Salvation Army"
    }

    def "SEARCH BY NAME | exact search with exact=true parameter should return strictly the exact organization"() {
        given: "organizations with similar names"
        def names = [
                "The Salvation Army",
                "The Salvation Army - Denver Citadel Corps",
                "Salvation Army Family Store"
        ]
        names.each { orgName ->
            executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, ?, true, 'VERIFIED')", UUID.randomUUID(), orgName)
        }

        when: "searching with exact=true flag"
        Page<Organization> results = organizationController.searchByName("The Salvation Army", true, Pageable.from(0, 10))

        then: "only the exact match is returned"
        results.content.size() == 1
        results.content[0].name == "The Salvation Army"
    }

    def "SEARCH BY NAME | case-insensitive exact search should match regardless of casing"() {
        given: "an organization with mixed case"
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army', true, 'VERIFIED')", UUID.randomUUID())
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army - Denver Citadel Corps', true, 'VERIFIED')", UUID.randomUUID())

        when: "searching with lowercase quoted string"
        Page<Organization> results = organizationController.searchByName('"the salvation army"', false, Pageable.from(0, 10))

        then: "it matches the organization case-insensitively"
        results.content.size() == 1
        results.content[0].name == "The Salvation Army"
    }

    def "SEARCH BY NAME | ranked search for 'Salvation Army' without quotes should return all matches with exact/prefix/word-boundary ranked at the top"() {
        given: "a rich cluster of Salvation Army organizations"
        def names = [
                "Friends of The Salvation Army",
                "The Salvation Army - Aurora Corps Community Center",
                "Salvation Army Family Store",
                "The Salvation Army Intermountain Division",
                "The Salvation Army",
                "The Salvation Army - Denver Citadel Corps",
                "Salvation Army Emergency Disaster Services",
                "Red Cross Unrelated Org"
        ]
        names.each { orgName ->
            executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, ?, true, 'VERIFIED')", UUID.randomUUID(), orgName)
        }

        when: "performing a non-exact ranked search"
        Page<Organization> results = organizationController.searchByName("Salvation Army", false, Pageable.from(0, 20))

        then: "all 7 Salvation Army organizations are returned, excluding the unrelated one"
        results.content.size() == 7
        results.content.every { it.name().contains("Salvation Army") }

        and: "The Salvation Army is ranked at the very top"
        results.content[0].name() == "The Salvation Army"
    }

    def "SEARCH BY NAME | prefix search 'The Salvation Army -' should return only Corps branches"() {
        given: "a cluster of organizations"
        def names = [
                "The Salvation Army",
                "The Salvation Army - Denver Citadel Corps",
                "The Salvation Army - Aurora Corps Community Center",
                "The Salvation Army Intermountain Division",
                "Salvation Army Family Store"
        ]
        names.each { orgName ->
            executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, ?, true, 'VERIFIED')", UUID.randomUUID(), orgName)
        }

        when: "searching for branch prefix"
        Page<Organization> results = organizationController.searchByName("The Salvation Army -", false, Pageable.from(0, 10))

        then: "only the two Corps branches are returned"
        results.content.size() == 2
        results.content.collect { it.name() }.sort() == [
                "The Salvation Army - Aurora Corps Community Center",
                "The Salvation Army - Denver Citadel Corps"
        ]
    }

    def "SEARCH BY NAME | blank or empty search query should return empty page"() {
        when: "searching with blank string"
        Page<Organization> results = organizationController.searchByName("   ", false, Pageable.from(0, 10))

        then: "an empty page is returned"
        results.content.isEmpty()
    }

    def "SEARCH BY NAME | non-matching query should return empty page"() {
        when: "searching for non-existent name"
        Page<Organization> results = organizationController.searchByName("NonExistentOrgXYZ999", false, Pageable.from(0, 10))

        then: "an empty page is returned"
        results.content.isEmpty()
    }

    def "SEARCH BY LOCATION | should query organizations by location coordinates, returning closest first"() {
        given: "3 organizations at varying distances from Denver center (-104.9903, 39.7392)"
        def orgNearId = UUID.randomUUID()
        def locNearId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org Near Denver', true, 'VERIFIED')", orgNearId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Loc Near', '123 St', 'Denver', 'US', ST_GeographyFromText('POINT(-104.9903 39.7572)'))", locNearId) // ~2 km
        executeUpdate("INSERT INTO organization_locations (organization_id, location_id) VALUES (?, ?)", orgNearId, locNearId)

        def orgMidId = UUID.randomUUID()
        def locMidId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org Mid Distance', true, 'VERIFIED')", orgMidId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Loc Mid', '456 St', 'Denver', 'US', ST_GeographyFromText('POINT(-104.9903 39.8292)'))", locMidId) // ~10 km
        executeUpdate("INSERT INTO organization_locations (organization_id, location_id) VALUES (?, ?)", orgMidId, locMidId)

        def orgFarId = UUID.randomUUID()
        def locFarId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org Far Boulder', true, 'VERIFIED')", orgFarId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Loc Far', '789 St', 'Boulder', 'US', ST_GeographyFromText('POINT(-105.2705 40.0150)'))", locFarId) // ~50 km
        executeUpdate("INSERT INTO organization_locations (organization_id, location_id) VALUES (?, ?)", orgFarId, locFarId)

        when: "searching within 15 km of Denver center"
        Page<Organization> results = organizationController.searchByLocation(-104.9903, 39.7392, 15000, Pageable.from(0, 10))

        then: "Near and Mid orgs are returned, Far is excluded, ordered by distance"
        results.content.size() == 2
        results.content[0].id() == orgNearId
        results.content[1].id() == orgMidId
    }
}

