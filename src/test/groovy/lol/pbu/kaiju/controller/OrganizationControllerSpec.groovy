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

    def "SEARCH BY NAME | fuzzy trigram search should tolerate typos like 'Slavation Army'"() {
        given: "a cluster of Salvation Army organizations and an unrelated org"
        def parentId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army', true, 'VERIFIED')", parentId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army - Denver Citadel Corps', true, 'VERIFIED')", UUID.randomUUID())
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Salvation Army Family Store', true, 'VERIFIED')", UUID.randomUUID())
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Red Cross Society', true, 'VERIFIED')", UUID.randomUUID())

        when: "searching with a typo: 'Slavation Army'"
        Page<Organization> results = organizationController.searchByName("Slavation Army", false, Pageable.from(0, 10))

        then: "The Salvation Army is matched via trigram similarity and ranked first"
        !results.content.isEmpty()
        results.content[0].id() == parentId
        results.content[0].name() == "The Salvation Army"
        results.content.every { it.name().contains("Salvation Army") }
    }

    def "SEARCH BY REGION | should return organizations operating within the specified administrative region"() {
        given: "two administrative regions"
        def region1Id = UUID.randomUUID()
        def region2Id = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Colorado Region', ST_GeographyFromText('POLYGON((-109 37, -102 37, -102 41, -109 41, -109 37))'))", region1Id)
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Utah Region', ST_GeographyFromText('POLYGON((-114 37, -109 37, -109 42, -114 42, -114 37))'))", region2Id)

        and: "organizations assigned to different regions"
        def org1Id = UUID.randomUUID()
        def org2Id = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Colorado Food Bank', true, 'VERIFIED')", org1Id)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Utah Food Bank', true, 'VERIFIED')", org2Id)

        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org1Id, region1Id)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org2Id, region2Id)

        when: "searching for organizations in Region 1"
        Page<Organization> results = organizationController.searchByRegion(region1Id, Pageable.from(0, 10))

        then: "only the organization operating in Region 1 is returned"
        results.content.size() == 1
        results.content[0].id() == org1Id
        results.content[0].name() == "Colorado Food Bank"
    }

    def "SEARCH SECURITY | should NOT return private organizations in name, location, or region search"() {
        given: "a private organization and a public organization with similar names"
        def publicOrgId = UUID.randomUUID()
        def privateOrgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Confidential Shelter Public', true, 'VERIFIED')", publicOrgId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Confidential Shelter Private', false, 'VERIFIED')", privateOrgId)

        and: "locations and regions assigned to both"
        def locPublicId = UUID.randomUUID()
        def locPrivateId = UUID.randomUUID()
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Loc Pub', '1 St', 'City', 'US', ST_GeographyFromText('POINT(-104.99 39.74)'))", locPublicId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Loc Priv', '2 St', 'City', 'US', ST_GeographyFromText('POINT(-104.99 39.74)'))", locPrivateId)
        executeUpdate("INSERT INTO organization_locations (organization_id, location_id) VALUES (?, ?)", publicOrgId, locPublicId)
        executeUpdate("INSERT INTO organization_locations (organization_id, location_id) VALUES (?, ?)", privateOrgId, locPrivateId)

        def regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Test Region Private', ST_GeographyFromText('POLYGON((-109 37, -102 37, -102 41, -109 41, -109 37))'))", regionId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", publicOrgId, regionId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", privateOrgId, regionId)

        when: "searching by name"
        def nameResults = organizationController.searchByName("Confidential Shelter", false, Pageable.from(0, 10))

        and: "searching by location"
        def locResults = organizationController.searchByLocation(-104.99, 39.74, 5000, Pageable.from(0, 10))

        and: "searching by region"
        def regionResults = organizationController.searchByRegion(regionId, Pageable.from(0, 10))

        then: "private organization is never returned in any search endpoint"
        nameResults.content.collect { it.id() } == [publicOrgId]
        locResults.content.collect { it.id() } == [publicOrgId]
        regionResults.content.collect { it.id() } == [publicOrgId]
    }

    def "SEARCH SECURITY | should NOT return unverified, suspended, or revoked organizations"() {
        given: "organizations in various verification statuses"
        def verifiedId = UUID.randomUUID()
        def unverifiedId = UUID.randomUUID()
        def suspendedId = UUID.randomUUID()
        def revokedId = UUID.randomUUID()

        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Charity Org Verified', true, 'VERIFIED')", verifiedId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Charity Org Unverified', true, 'UNVERIFIED')", unverifiedId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Charity Org Suspended', true, 'SUSPENDED')", suspendedId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Charity Org Revoked', true, 'REVOKED')", revokedId)

        when: "searching by name"
        def results = organizationController.searchByName("Charity Org", false, Pageable.from(0, 10))

        then: "only the verified organization is returned"
        results.content.collect { it.id() } == [verifiedId]
    }

    def "SEARCH SECURITY | SQL wildcard queries ('%', '_', '%%') should return empty page rather than dumping table"() {
        given: "some organizations exist in the database"
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Wildcard Test Org A', true, 'VERIFIED')", UUID.randomUUID())
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Wildcard Test Org B', true, 'VERIFIED')", UUID.randomUUID())

        when: "searching with wildcard characters"
        def percentResults = organizationController.searchByName("%", false, Pageable.from(0, 10))
        def underscoreResults = organizationController.searchByName("_", false, Pageable.from(0, 10))
        def multiWildcardResults = organizationController.searchByName(" %_% ", false, Pageable.from(0, 10))
        def emptyQuotesResults = organizationController.searchByName('""', false, Pageable.from(0, 10))

        then: "all wildcard-only and empty quote queries return empty results"
        percentResults.content.isEmpty()
        underscoreResults.content.isEmpty()
        multiWildcardResults.content.isEmpty()
        emptyQuotesResults.content.isEmpty()
    }

    /********** EXHAUSTIVE QUERY VARIATIONS & EDGE CASES **********/

    def "SEARCH BY NAME | exhaustive syntax permutations: quote variations, padding, and mismatched quotes"() {
        given: "an organization in database"
        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army - Denver Citadel Corps', true, 'VERIFIED')", UUID.randomUUID())

        when: "searching with leading/trailing spaces outside quotes: '  \"The Salvation Army\"  '"
        def resPaddedQuotes = organizationController.searchByName('  "The Salvation Army"  ', false, Pageable.from(0, 10))

        and: "searching with spaces inside quotes: '\"  The Salvation Army  \"'"
        def resSpacesInside = organizationController.searchByName('"  The Salvation Army  "', false, Pageable.from(0, 10))

        and: "searching with single-quote character only: '\'' and '\"'"
        def resSingleQuoteOnly = organizationController.searchByName("'", false, Pageable.from(0, 10))
        def resDoubleQuoteOnly = organizationController.searchByName('"', false, Pageable.from(0, 10))

        and: "searching with empty quotes containing whitespace: '\"   \"' and '\'   \''"
        def resEmptyDoubleWithSpaces = organizationController.searchByName('"   "', false, Pageable.from(0, 10))
        def resEmptySingleWithSpaces = organizationController.searchByName("'   '", false, Pageable.from(0, 10))

        and: "searching with mismatched opening quote: '\"The Salvation Army'"
        def resMismatchedDoubleStart = organizationController.searchByName('"The Salvation Army', false, Pageable.from(0, 10))

        and: "searching with mismatched closing quote: 'The Salvation Army\"'"
        def resMismatchedDoubleEnd = organizationController.searchByName('The Salvation Army"', false, Pageable.from(0, 10))

        and: "searching with mismatched opening single quote: '\'The Salvation Army'"
        def resMismatchedSingleStart = organizationController.searchByName("'The Salvation Army", false, Pageable.from(0, 10))

        and: "searching with mismatched closing single quote: 'The Salvation Army\''"
        def resMismatchedSingleEnd = organizationController.searchByName("The Salvation Army'", false, Pageable.from(0, 10))

        and: "searching with exact=true AND quoted together"
        def resExactAndQuoted = organizationController.searchByName('"The Salvation Army"', true, Pageable.from(0, 10))

        then: "exact quote variations return strictly the single exact parent org"
        resPaddedQuotes.content.size() == 1
        resPaddedQuotes.content[0].id() == orgId

        resSpacesInside.content.size() == 1
        resSpacesInside.content[0].id() == orgId

        resExactAndQuoted.content.size() == 1
        resExactAndQuoted.content[0].id() == orgId

        and: "single quote characters and empty quote queries return empty pages"
        resSingleQuoteOnly.content.isEmpty()
        resDoubleQuoteOnly.content.isEmpty()
        resEmptyDoubleWithSpaces.content.isEmpty()
        resEmptySingleWithSpaces.content.isEmpty()

        and: "mismatched quotes gracefully fall back to ranked search and surface parent org at top"
        !resMismatchedDoubleStart.content.isEmpty()
        resMismatchedDoubleStart.content[0].id() == orgId

        !resMismatchedDoubleEnd.content.isEmpty()
        resMismatchedDoubleEnd.content[0].id() == orgId

        !resMismatchedSingleStart.content.isEmpty()
        resMismatchedSingleStart.content[0].id() == orgId

        !resMismatchedSingleEnd.content.isEmpty()
        resMismatchedSingleEnd.content[0].id() == orgId
    }

    def "SEARCH BY NAME | exhaustive typo permutations: subtle misspellings and transposed letters"() {
        given: "Salvation Army parent organization"
        def parentId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army', true, 'VERIFIED')", parentId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army - Denver Citadel Corps', true, 'VERIFIED')", UUID.randomUUID())

        when: "searching with transposed 'v' and 't': 'Salavtion Army'"
        def resTransposedVt = organizationController.searchByName("Salavtion Army", false, Pageable.from(0, 10))

        and: "searching with transposed 'm' and 'r': 'Salvation Amry'"
        def resTransposedMr = organizationController.searchByName("Salvation Amry", false, Pageable.from(0, 10))

        and: "searching with typo and article: 'The Slavation Army'"
        def resTypoWithArticle = organizationController.searchByName("The Slavation Army", false, Pageable.from(0, 10))

        then: "all typo variations successfully find The Salvation Army via trigram similarity"
        !resTransposedVt.content.isEmpty()
        resTransposedVt.content[0].id() == parentId

        !resTransposedMr.content.isEmpty()
        resTransposedMr.content[0].id() == parentId

        !resTypoWithArticle.content.isEmpty()
        resTypoWithArticle.content[0].id() == parentId
    }

    def "SEARCH BY NAME | article normalization with 'An' and 'A' leading articles"() {
        given: "organizations with 'An' and 'A' prefixes"
        def orgAnId = UUID.randomUUID()
        def orgAId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'An Organization Example', true, 'VERIFIED')", orgAnId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'A Better Tomorrow Foundation', true, 'VERIFIED')", orgAId)

        when: "searching without the leading article"
        def resAn = organizationController.searchByName("Organization Example", false, Pageable.from(0, 10))
        def resA = organizationController.searchByName("Better Tomorrow Foundation", false, Pageable.from(0, 10))

        then: "article normalization correctly ranks the parent entity at the top"
        !resAn.content.isEmpty()
        resAn.content[0].id() == orgAnId

        !resA.content.isEmpty()
        resA.content[0].id() == orgAId
    }

    def "SEARCH BY NAME | literal SQL wildcard characters (% and _) in organization names"() {
        given: "organizations with literal % and _ in their names"
        def orgPercentId = UUID.randomUUID()
        def orgUnderscoreId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, '100% Volunteer Initiative', true, 'VERIFIED')", orgPercentId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Special_Ops Outreach', true, 'VERIFIED')", orgUnderscoreId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'SpecialXOps Outreach', true, 'VERIFIED')", UUID.randomUUID())

        when: "searching for literal '100%'"
        def resPercent = organizationController.searchByName("100%", false, Pageable.from(0, 10))

        and: "searching for literal 'Special_Ops'"
        def resUnderscore = organizationController.searchByName("Special_Ops", false, Pageable.from(0, 10))

        then: "literal percent matches only the organization containing '100%'"
        resPercent.content.size() == 1
        resPercent.content[0].id() == orgPercentId

        and: "literal underscore does not expand as a single-character wildcard (SpecialXOps is not matched)"
        resUnderscore.content.size() == 1
        resUnderscore.content[0].id() == orgUnderscoreId
    }

    def "SEARCH BY NAME | exhaustive cluster branch and keyword queries"() {
        given: "a rich cluster of diverse Salvation Army organizations"
        def parentId = UUID.randomUUID()
        def denverId = UUID.randomUUID()
        def auroraId = UUID.randomUUID()
        def springsId = UUID.randomUUID()
        def intermountainId = UUID.randomUUID()
        def storeId = UUID.randomUUID()
        def disasterId = UUID.randomUUID()
        def friendsId = UUID.randomUUID()

        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army', true, 'VERIFIED')", parentId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army - Denver Citadel Corps', true, 'VERIFIED')", denverId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army - Aurora Corps Community Center', true, 'VERIFIED')", auroraId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army - Colorado Springs Corps', true, 'VERIFIED')", springsId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army Intermountain Division', true, 'VERIFIED')", intermountainId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Salvation Army Family Store', true, 'VERIFIED')", storeId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Salvation Army Emergency Disaster Services', true, 'VERIFIED')", disasterId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Friends of The Salvation Army', true, 'VERIFIED')", friendsId)

        when: "searching for 'Denver'"
        def resDenver = organizationController.searchByName("Denver", false, Pageable.from(0, 10))

        and: "searching for 'Aurora'"
        def resAurora = organizationController.searchByName("Aurora", false, Pageable.from(0, 10))

        and: "searching for 'Colorado Springs'"
        def resSprings = organizationController.searchByName("Colorado Springs", false, Pageable.from(0, 10))

        and: "searching for 'Intermountain'"
        def resIntermountain = organizationController.searchByName("Intermountain", false, Pageable.from(0, 10))

        and: "searching for 'Family Store'"
        def resStore = organizationController.searchByName("Family Store", false, Pageable.from(0, 10))

        and: "searching for 'Emergency Disaster'"
        def resDisaster = organizationController.searchByName("Emergency Disaster", false, Pageable.from(0, 10))

        and: "searching for 'Friends of'"
        def resFriends = organizationController.searchByName("Friends of", false, Pageable.from(0, 10))

        then: "each specific query accurately isolates its target entity at the top of results"
        resDenver.content[0].id() == denverId
        resAurora.content[0].id() == auroraId
        resSprings.content[0].id() == springsId
        resIntermountain.content[0].id() == intermountainId
        resStore.content[0].id() == storeId
        resDisaster.content[0].id() == disasterId
        resFriends.content[0].id() == friendsId
    }

    def "PAGINATION & SORTING | normalizePageable and getOrganizations branch coverage"() {
        given: "organizations exist"
        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Paging Test Org Alpha', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Paging Test Org Beta', true, 'VERIFIED')", UUID.randomUUID())

        when: "calling getOrganizations with null pageable"
        def resGetOrgsNull = organizationController.getOrganizations(null)

        and: "calling getOrganizations with unpaged pageable"
        def resGetOrgsUnpaged = organizationController.getOrganizations(CursoredPageable.from(Sort.of(Sort.Order.asc("name"))).withoutPaging())

        and: "calling searchByName with null pageable"
        def resNameNullPageable = organizationController.searchByName("Paging Test Org", false, null)

        and: "calling searchByName with Pageable.unpaged()"
        def resNameUnpaged = organizationController.searchByName("Paging Test Org", false, Pageable.unpaged())

        and: "calling searchByName with client-specified sort (which should be stripped to preserve relevance order)"
        def sortedPageable = Pageable.from(0, 10, Sort.of(Sort.Order.desc("name")))
        def resNameWithSort = organizationController.searchByName("Paging Test Org", false, sortedPageable)

        and: "calling searchByNameExact with client-specified sort (where stripSort is false)"
        def resExactWithSort = organizationController.searchByName('"Paging Test Org Alpha"', false, sortedPageable)

        and: "calling searchByLocation with null, unpaged, and sorted pageables"
        def resLocNull = organizationController.searchByLocation(-104.99, 39.74, 50000, null)
        def resLocUnpaged = organizationController.searchByLocation(-104.99, 39.74, 50000, Pageable.unpaged())
        def resLocSorted = organizationController.searchByLocation(-104.99, 39.74, 50000, sortedPageable)

        and: "calling searchByRegion with null, unpaged, and sorted pageables"
        def regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Paging Region', ST_GeographyFromText('POLYGON((-109 37, -102 37, -102 41, -109 41, -109 37))'))", regionId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgId, regionId)
        def resRegionNull = organizationController.searchByRegion(regionId, null)
        def resRegionUnpaged = organizationController.searchByRegion(regionId, Pageable.unpaged())
        def resRegionSorted = organizationController.searchByRegion(regionId, sortedPageable)

        then: "all permutations execute successfully with defaults"
        !resGetOrgsNull.content.isEmpty()
        !resGetOrgsUnpaged.content.isEmpty()
        !resNameNullPageable.content.isEmpty()
        !resNameUnpaged.content.isEmpty()
        !resNameWithSort.content.isEmpty()
        !resExactWithSort.content.isEmpty()
        resLocNull != null
        resLocUnpaged != null
        resLocSorted != null
        !resRegionNull.content.isEmpty()
        !resRegionUnpaged.content.isEmpty()
        !resRegionSorted.content.isEmpty()
    }
}

