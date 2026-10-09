package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.uri.UriBuilder
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.dto.CreateOrganizationCommand
import lol.pbu.kaiju.dto.UpdateOrganizationCommand
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.OrganizationRepository
import net.datafaker.Faker
import spock.lang.Shared
import spock.lang.Unroll

import java.util.UUID

import static lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class OrganizationControllerSpec extends BaseControllerSpec {

    @Inject
    OrganizationRepository organizationRepository

    @Inject
    OrganizationController organizationController

    @Shared
    Faker faker = new Faker()

    @Shared
    UUID adminId = UUID.randomUUID()

    @Shared
    List<UUID> createdUserIds = []

    def setupSpec() {
        executeUpdate("INSERT INTO users (id, email, role) VALUES ('00000000-0000-0000-0000-000000000000', 'global-admin@example.com', 'GLOBAL_ADMIN') ON CONFLICT DO NOTHING")
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, 'org-admin-test@example.com', 'GLOBAL_ADMIN') ON CONFLICT DO NOTHING", adminId)
    }

    def cleanupSpec() {
        executeUpdate("DELETE FROM users WHERE id = ?", adminId)
    }

    def cleanup() {
        executeUpdate("DELETE FROM organization_locations")
        executeUpdate("DELETE FROM organization_regions")
        createdUserIds.each { id ->
            try {
                executeUpdate("DELETE FROM organization_users WHERE user_id = ?", id)
                executeUpdate("DELETE FROM users WHERE id = ?", id)
            } catch (Exception ignored) {
            }
        }
        createdUserIds.clear()
        sql.execute("""
            DELETE FROM organizations 
            WHERE name LIKE '%Salvation Army%' 
               OR name LIKE '%Shelter%' 
               OR name LIKE '%Charity Org%' 
               OR name LIKE '%Wildcard%' 
               OR name LIKE '%Volunteer Initiative%' 
               OR name LIKE 'Special%' 
               OR name LIKE 'Org Near%' 
               OR name LIKE 'Org Mid%' 
               OR name LIKE 'Org Far%' 
               OR name LIKE '%Food Bank%' 
               OR name LIKE 'Paging Test Org%' 
               OR name LIKE '%Organization Example%' 
               OR name LIKE '%Better Tomorrow%' 
               OR name LIKE 'Test Org%' 
               OR name LIKE 'Original%' 
               OR name LIKE 'Updated%' 
               OR name LIKE 'Temporary%' 
               OR name LIKE 'Auth Org%' 
               OR name LIKE 'Delete Auth Org%' 
               OR name LIKE 'Allowed Org%' 
               OR name LIKE 'Std Org%' 
               OR name LIKE 'Std Del Org%' 
               OR name LIKE 'Hacked%' 
               OR name LIKE 'Valid Org%' 
               OR name LIKE 'Valid Name%' 
               OR name LIKE 'Red Cross%'
        """ as String)
        sql.execute("DELETE FROM locations WHERE name IN ('Loc Near', 'Loc Mid', 'Loc Far', 'Loc Pub', 'Loc Priv', 'Paging Test Loc')" as String)
        sql.execute("DELETE FROM administrative_regions WHERE name IN ('Colorado Region', 'Utah Region', 'Test Region Private', 'Paging Region')" as String)
    }

    def setup() {
        cleanup()
    }

    static class PageResponse<T> {
        List<T> content = []
        int totalSize

        boolean isEmpty() {
            content == null || content.isEmpty()
        }
    }

    private PageResponse<Organization> toPage(Map body) {
        if (!body) {
            return new PageResponse<Organization>(content: [], totalSize: 0)
        }
        List items = (body.content as List) ?: []
        List<Organization> orgs = items.collect { Map item ->
            new Organization(
                    item.id ? UUID.fromString(item.id as String) : null,
                    item.name as String,
                    item.websiteUrl as String,
                    item.parentId ? UUID.fromString(item.parentId as String) : null,
                    item.isPublic != null ? (item.isPublic as Boolean) : true,
                    item.verificationStatus ? VerificationStatus.valueOf(item.verificationStatus as String) : null,
                    null,
                    []
            )
        }
        int total = body.totalSize != null ? (body.totalSize as int) : orgs.size()
        new PageResponse<Organization>(content: orgs, totalSize: total)
    }

    private PageResponse<Organization> searchByName(String name, boolean exact = false, Pageable pageable = null) {
        def builder = UriBuilder.of("/organizations/search-by-name")
                .queryParam("name", name)
                .queryParam("exact", exact)
        if (pageable != null && !pageable.isUnpaged()) {
            builder.queryParam("page", pageable.number)
            builder.queryParam("size", pageable.size)
            if (pageable.sort != null && !pageable.sort.orderBy.isEmpty()) {
                pageable.sort.orderBy.each { order ->
                    builder.queryParam("sort", "${order.property},${order.direction.name().toLowerCase()}")
                }
            }
        }
        def uri = builder.build().toString()
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri), adminId.toString()), Map)
        toPage(response.body())
    }

    private PageResponse<Organization> searchByLocation(double longitude, double latitude, double radiusMeters, Pageable pageable = null) {
        def builder = UriBuilder.of("/organizations/search-by-location")
                .queryParam("longitude", longitude)
                .queryParam("latitude", latitude)
                .queryParam("radiusMeters", radiusMeters)
        if (pageable != null && !pageable.isUnpaged()) {
            builder.queryParam("page", pageable.number)
            builder.queryParam("size", pageable.size)
            if (pageable.sort != null && !pageable.sort.orderBy.isEmpty()) {
                pageable.sort.orderBy.each { order ->
                    builder.queryParam("sort", "${order.property},${order.direction.name().toLowerCase()}")
                }
            }
        }
        def uri = builder.build().toString()
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri), adminId.toString()), Map)
        toPage(response.body())
    }

    private PageResponse<Organization> searchByRegion(UUID regionId, Pageable pageable = null) {
        def builder = UriBuilder.of("/organizations/search-by-region")
                .queryParam("regionId", regionId)
        if (pageable != null && !pageable.isUnpaged()) {
            builder.queryParam("page", pageable.number)
            builder.queryParam("size", pageable.size)
            if (pageable.sort != null && !pageable.sort.orderBy.isEmpty()) {
                pageable.sort.orderBy.each { order ->
                    builder.queryParam("sort", "${order.property},${order.direction.name().toLowerCase()}")
                }
            }
        }
        def uri = builder.build().toString()
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri), adminId.toString()), Map)
        toPage(response.body())
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid organization"() {
        given: "a new valid organization command"
        def command = new CreateOrganizationCommand(
                "Test Org ${faker.company().name()}",
                "https://${faker.internet().domainName()}",
                null,
                true
        )

        when: "the organization is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/organizations", command), adminId.toString()), Organization)
        Organization saved = response.body()

        then: "the organization is persisted with a generated ID"
        response.status == HttpStatus.OK
        verifyAll {
            saved.id() != null
            saved.name() == command.name()
            saved.websiteUrl() == command.websiteUrl()
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
    def "CREATE | should fail to save organization with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add an organization with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/organizations", payload), adminId.toString()), Organization)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase               | payload
        "Null Name"            | [name: null, websiteUrl: "https://example.com", isPublic: true]
        "Blank Name"           | [name: "   ", websiteUrl: "https://example.com", isPublic: true]
        "Name Too Long"        | [name: "A" * 256, websiteUrl: "https://example.com", isPublic: true]
        "Website URL Too Long" | [name: "Valid Name", websiteUrl: "A" * 256, isPublic: true]
        "Null isPublic"        | [name: "Valid Name", websiteUrl: "https://example.com", isPublic: null]
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing organization by ID"() {
        given: "an existing organization"
        def org = organizationRepository.save(new Organization(null, "Test Organization Read", "https://example.com", null, true, UNVERIFIED, null, []))
        UUID id = org.id()

        when: "the organization is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organizations/${id}"), adminId.toString()), Organization)

        then: "the correct organization is returned"
        response.status == HttpStatus.OK
        verifyAll {
            response.body().id() == id
            response.body().name() == "Test Organization Read"
        }
    }

    def "READ | should return 404 for a non-existent organization ID"() {
        when: "a non-existent organization is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/organizations/${UUID.randomUUID()}"), adminId.toString()), Organization)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing organization"() {
        given: "an existing organization"
        def org = organizationRepository.save(new Organization(null, "Original Org Name", "https://example.com", null, true, UNVERIFIED, null, []))
        UUID id = org.id()
        def newName = "Updated ${faker.company().name()}"
        def newUrl = "https://${faker.internet().domainName()}"
        def updateCommand = new UpdateOrganizationCommand(newName, newUrl, null, true)

        when: "the organization is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/organizations/${id}", updateCommand), adminId.toString()), Organization)
        Organization updated = response.body()

        then: "the returned organization contains the updated data"
        response.status == HttpStatus.OK
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
        given: "a random non-existent ID and an update command"
        def nonExistentId = UUID.randomUUID()
        def updateCommand = new UpdateOrganizationCommand("Test Org", "https://example.com", null, true)

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/organizations/${nonExistentId}", updateCommand), adminId.toString()), Organization)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    @Unroll
    def "UPDATE | should fail to update organization with invalid data: #testCase"(String testCase, Map payload) {
        given: "an existing organization"
        def org = organizationRepository.save(new Organization(null, "Valid Org For Update", "https://example.com", null, true, UNVERIFIED, null, []))

        when: "an update with invalid data is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/organizations/${org.id()}", payload), adminId.toString()), Organization)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase               | payload
        "Blank Name"           | [name: "   ", websiteUrl: "https://example.com", isPublic: true]
        "Name Too Long"        | [name: "A" * 256, websiteUrl: "https://example.com", isPublic: true]
        "Website URL Too Long" | [name: "Valid Name", websiteUrl: "A" * 256, isPublic: true]
        "Null isPublic"        | [name: "Valid Name", websiteUrl: "https://example.com", isPublic: null]
    }

    @Unroll
    def "UPDATE | should prevent mass assignment vulnerabilities: #testCase"(String testCase, Map payload, boolean shouldNameChange, VerificationStatus expectedStatus) {
        given: "an existing organization"
        def org = organizationRepository.save(new Organization(null, "Original Name", "https://example.com", null, true, UNVERIFIED, null, []))
        UUID id = org.id()

        when: "an update is submitted via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/organizations/${id}", payload), adminId.toString()), Organization)
        Organization updated = response.body()

        then: "the safe fields are updated correctly"
        updated.name() == (shouldNameChange ? payload.name : "Original Name")

        and: "the sensitive fields are NOT updated"
        updated.verificationStatus() == expectedStatus

        where:
        testCase                           | payload                                                                                                   || shouldNameChange | expectedStatus
        "Change safe field only"           | [name: "New Name", websiteUrl: "https://example.com", isPublic: true]                                     || true             | UNVERIFIED
        "Attempt to escalate verification" | [name: "Original Name", websiteUrl: "https://example.com", isPublic: true, verificationStatus: "VERIFIED"] || false            | UNVERIFIED
        "Change safe and attempt escalate" | [name: "Hacked Name", websiteUrl: "https://example.com", isPublic: true, verificationStatus: "VERIFIED"]   || true             | UNVERIFIED
        "Attempt to set REVOKED"           | [name: "Original Name", websiteUrl: "https://example.com", isPublic: true, verificationStatus: "REVOKED"]  || false            | UNVERIFIED
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing organization"() {
        given: "a new organization to be deleted"
        def command = new CreateOrganizationCommand(
                "Temporary Org to Delete",
                "https://example.org",
                null,
                true
        )
        def createResponse = client.exchange(asGlobalAdmin(HttpRequest.POST("/organizations", command), adminId.toString()), Organization)
        UUID id = createResponse.body().id()
        assert organizationRepository.existsById(id)

        when: "the organization is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/organizations/${id}"), adminId.toString()))

        then: "the organization no longer exists in the repository or database"
        response.status == HttpStatus.OK
        verifyAll {
            !organizationRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM organizations WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent organization"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/organizations/${nonExistentId}"), adminId.toString()))

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
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

    def "LIST | should retrieve organizations with pagination via HTTP GET"() {
        when: "requesting organizations via HTTP GET as global admin"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organizations?size=5"), adminId.toString()), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
        body.content.size() >= 2
    }

    def "LIST | should retrieve organizations with pagination and sorting via HTTP GET"() {
        when: "requesting organizations via HTTP GET as global admin with sort"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organizations?size=5&sort=name,asc"), adminId.toString()), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
        body.content.size() >= 2
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
        PageResponse<Organization> results = searchByName('"The Salvation Army"', false, Pageable.from(0, 10))

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
        PageResponse<Organization> results = searchByName("'The Salvation Army'", false, Pageable.from(0, 10))

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
        PageResponse<Organization> results = searchByName("The Salvation Army", true, Pageable.from(0, 10))

        then: "only the exact match is returned"
        results.content.size() == 1
        results.content[0].name == "The Salvation Army"
    }

    def "SEARCH BY NAME | case-insensitive exact search should match regardless of casing"() {
        given: "an organization with mixed case"
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army', true, 'VERIFIED')", UUID.randomUUID())
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'The Salvation Army - Denver Citadel Corps', true, 'VERIFIED')", UUID.randomUUID())

        when: "searching with lowercase quoted string"
        PageResponse<Organization> results = searchByName('"the salvation army"', false, Pageable.from(0, 10))

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
        PageResponse<Organization> results = searchByName("Salvation Army", false, Pageable.from(0, 20))

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
        PageResponse<Organization> results = searchByName("The Salvation Army -", false, Pageable.from(0, 10))

        then: "only the two Corps branches are returned"
        results.content.size() == 2
        results.content.collect { it.name() }.sort() == [
                "The Salvation Army - Aurora Corps Community Center",
                "The Salvation Army - Denver Citadel Corps"
        ]
    }

    def "SEARCH BY NAME | blank or empty search query should return empty page"() {
        when: "searching with blank string"
        PageResponse<Organization> results = searchByName("   ", false, Pageable.from(0, 10))

        then: "an empty page is returned"
        results.content.isEmpty()
    }

    def "SEARCH BY NAME | non-matching query should return empty page"() {
        when: "searching for non-existent name"
        PageResponse<Organization> results = searchByName("NonExistentOrgXYZ999", false, Pageable.from(0, 10))

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
        PageResponse<Organization> results = searchByLocation(-104.9903, 39.7392, 15000, Pageable.from(0, 10))

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
        PageResponse<Organization> results = searchByName("Slavation Army", false, Pageable.from(0, 10))

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
        PageResponse<Organization> results = searchByRegion(region1Id, Pageable.from(0, 10))

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
        def nameResults = searchByName("Confidential Shelter", false, Pageable.from(0, 10))

        and: "searching by location"
        def locResults = searchByLocation(-104.99, 39.74, 5000, Pageable.from(0, 10))

        and: "searching by region"
        def regionResults = searchByRegion(regionId, Pageable.from(0, 10))

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
        def results = searchByName("Charity Org", false, Pageable.from(0, 10))

        then: "only the verified organization is returned"
        results.content.collect { it.id() } == [verifiedId]
    }

    def "SEARCH SECURITY | SQL wildcard queries ('%', '_', '%%') should return empty page rather than dumping table"() {
        given: "some organizations exist in the database"
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Wildcard Test Org A', true, 'VERIFIED')", UUID.randomUUID())
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Wildcard Test Org B', true, 'VERIFIED')", UUID.randomUUID())

        when: "searching with wildcard characters"
        def percentResults = searchByName("%", false, Pageable.from(0, 10))
        def underscoreResults = searchByName("_", false, Pageable.from(0, 10))
        def multiWildcardResults = searchByName(" %_% ", false, Pageable.from(0, 10))
        def emptyQuotesResults = searchByName('""', false, Pageable.from(0, 10))

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
        def resPaddedQuotes = searchByName('  "The Salvation Army"  ', false, Pageable.from(0, 10))

        and: "searching with spaces inside quotes: '\"  The Salvation Army  \"'"
        def resSpacesInside = searchByName('"  The Salvation Army  "', false, Pageable.from(0, 10))

        and: "searching with single-quote character only: '\'' and '\"'"
        def resSingleQuoteOnly = searchByName("'", false, Pageable.from(0, 10))
        def resDoubleQuoteOnly = searchByName('"', false, Pageable.from(0, 10))

        and: "searching with empty quotes containing whitespace: '\"   \"' and '\'   \''"
        def resEmptyDoubleWithSpaces = searchByName('"   "', false, Pageable.from(0, 10))
        def resEmptySingleWithSpaces = searchByName("'   '", false, Pageable.from(0, 10))

        and: "searching with mismatched opening quote: '\"The Salvation Army'"
        def resMismatchedDoubleStart = searchByName('"The Salvation Army', false, Pageable.from(0, 10))

        and: "searching with mismatched closing quote: 'The Salvation Army\"'"
        def resMismatchedDoubleEnd = searchByName('The Salvation Army"', false, Pageable.from(0, 10))

        and: "searching with mismatched opening single quote: '\'The Salvation Army'"
        def resMismatchedSingleStart = searchByName("'The Salvation Army", false, Pageable.from(0, 10))

        and: "searching with mismatched closing single quote: 'The Salvation Army\''"
        def resMismatchedSingleEnd = searchByName("The Salvation Army'", false, Pageable.from(0, 10))

        and: "searching with exact=true AND quoted together"
        def resExactAndQuoted = searchByName('"The Salvation Army"', true, Pageable.from(0, 10))

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
        def resTransposedVt = searchByName("Salavtion Army", false, Pageable.from(0, 10))

        and: "searching with transposed 'm' and 'r': 'Salvation Amry'"
        def resTransposedMr = searchByName("Salvation Amry", false, Pageable.from(0, 10))

        and: "searching with typo and article: 'The Slavation Army'"
        def resTypoWithArticle = searchByName("The Slavation Army", false, Pageable.from(0, 10))

        then: "all typo variations successfully find The Salvation Army via trigram similarity"
        !resTransposedVt.content.isEmpty()
        resTransposedVt.content[0].id() == parentId

        !resTransposedMr.content.isEmpty()
        resTransposedMr.content[0].id() == parentId

        !resTypoWithArticle.content.isEmpty()
        resTypoWithArticle.content[0].id() == parentId
    }

    def "SEARCH BY NAME | article normalization with 'An' and 'A' leading articles"() {
        given: "organizations with 'An' and 'A' prefixes alongside competing prefix organizations"
        def orgAnId = UUID.randomUUID()
        def orgAId = UUID.randomUUID()
        def compAnId = UUID.randomUUID()
        def compAId = UUID.randomUUID()

        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'An Organization Example', true, 'VERIFIED')", orgAnId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'A Better Tomorrow Foundation', true, 'VERIFIED')", orgAId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Organization Example Regional Branch', true, 'VERIFIED')", compAnId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Better Tomorrow Foundation Network', true, 'VERIFIED')", compAId)

        when: "searching without the leading article"
        def resAn = searchByName("Organization Example", false, Pageable.from(0, 10))
        def resA = searchByName("Better Tomorrow Foundation", false, Pageable.from(0, 10))

        then: "article normalization correctly ranks the parent entity with stripped article above the competitor"
        resAn.content.size() == 2
        resAn.content[0].id() == orgAnId
        resAn.content[1].id() == compAnId

        and: "second article query similarly elevates the canonical parent entity"
        resA.content.size() == 2
        resA.content[0].id() == orgAId
        resA.content[1].id() == compAId
    }

    def "SEARCH BY NAME | literal SQL wildcard characters (% and _) in organization names"() {
        given: "organizations with literal % and _ in their names"
        def orgPercentId = UUID.randomUUID()
        def orgUnderscoreId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, '100% Volunteer Initiative', true, 'VERIFIED')", orgPercentId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Special_Ops Outreach', true, 'VERIFIED')", orgUnderscoreId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'SpecialXOps Outreach', true, 'VERIFIED')", UUID.randomUUID())

        when: "searching for literal '100%'"
        def resPercent = searchByName("100%", false, Pageable.from(0, 10))

        and: "searching for literal 'Special_Ops'"
        def resUnderscore = searchByName("Special_Ops", false, Pageable.from(0, 10))

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
        def resDenver = searchByName("Denver", false, Pageable.from(0, 10))

        and: "searching for 'Aurora'"
        def resAurora = searchByName("Aurora", false, Pageable.from(0, 10))

        and: "searching for 'Colorado Springs'"
        def resSprings = searchByName("Colorado Springs", false, Pageable.from(0, 10))

        and: "searching for 'Intermountain'"
        def resIntermountain = searchByName("Intermountain", false, Pageable.from(0, 10))

        and: "searching for 'Family Store'"
        def resStore = searchByName("Family Store", false, Pageable.from(0, 10))

        and: "searching for 'Emergency Disaster'"
        def resDisaster = searchByName("Emergency Disaster", false, Pageable.from(0, 10))

        and: "searching for 'Friends of'"
        def resFriends = searchByName("Friends of", false, Pageable.from(0, 10))

        and: "searching for 'Salvation Army' ranks parent organization at top"
        def resParent = searchByName("Salvation Army", false, Pageable.from(0, 10))

        then: "each specific query accurately isolates its target entity at the top of results"
        resParent.content[0].id() == parentId
        resDenver.content[0].id() == denverId
        resAurora.content[0].id() == auroraId
        resSprings.content[0].id() == springsId
        resIntermountain.content[0].id() == intermountainId
        resStore.content[0].id() == storeId
        resDisaster.content[0].id() == disasterId
        resFriends.content[0].id() == friendsId
    }

    def "PAGINATION & SORTING | normalizePageable and getOrganizations branch coverage"() {
        given: "organizations, locations, and regions exist"
        def org1Id = UUID.randomUUID()
        def org2Id = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Paging Test Org 1', true, 'VERIFIED')", org1Id)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Paging Test Org 2', true, 'VERIFIED')", org2Id)

        def locId = UUID.randomUUID()
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Paging Test Loc', '100 Main St', 'Denver', 'US', ST_GeographyFromText('POINT(-104.99 39.74)'))", locId)
        executeUpdate("INSERT INTO organization_locations (organization_id, location_id) VALUES (?, ?)", org1Id, locId)

        def regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Paging Region', ST_GeographyFromText('POLYGON((-109 37, -102 37, -102 41, -109 41, -109 37))'))", regionId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org1Id, regionId)

        when: "calling getOrganizations with null pageable"
        def resGetOrgsNull = organizationController.getOrganizations(null)

        and: "calling getOrganizations with unpaged pageable"
        def resGetOrgsUnpaged = organizationController.getOrganizations(CursoredPageable.from(Sort.of(Sort.Order.asc("name"))).withoutPaging())

        and: "calling searchByName with null pageable"
        def resNameNullPageable = organizationController.searchByName("Paging Test Org", false, null)

        and: "calling searchByName with Pageable.unpaged()"
        def resNameUnpaged = organizationController.searchByName("Paging Test Org", false, Pageable.unpaged())

        and: "calling searchByName with client-specified sort (which should be stripped to preserve native relevance order)"
        def sortedPageable = Pageable.from(0, 10, Sort.of(Sort.Order.desc("name")))
        def resNameWithSort = organizationController.searchByName("Paging Test Org", false, sortedPageable)

        and: "calling searchByNameExact with client-specified sort (which strips sort to prevent duplicate ORDER BY)"
        def resExactWithSort = organizationController.searchByName('"Paging Test Org 1"', false, sortedPageable)

        and: "calling searchByLocation with null, unpaged, and sorted pageables"
        def resLocNull = organizationController.searchByLocation(-104.99, 39.74, 50000, null)
        def resLocUnpaged = organizationController.searchByLocation(-104.99, 39.74, 50000, Pageable.unpaged())
        def resLocSorted = organizationController.searchByLocation(-104.99, 39.74, 50000, sortedPageable)

        and: "calling searchByRegion with null, unpaged, and sorted pageables"
        def resRegionNull = organizationController.searchByRegion(regionId, null)
        def resRegionUnpaged = organizationController.searchByRegion(regionId, Pageable.unpaged())
        def resRegionSorted = organizationController.searchByRegion(regionId, sortedPageable)

        then: "all permutations execute successfully with defaults"
        !resGetOrgsNull.content.isEmpty()
        !resGetOrgsUnpaged.content.isEmpty()
        !resNameNullPageable.content.isEmpty()
        !resNameUnpaged.content.isEmpty()

        and: "searchByName with client DESC sort strips the client sort and maintains native relevance ASC tie-breaking"
        resNameWithSort.content*.name() == ['Paging Test Org 1', 'Paging Test Org 2']

        and: "searchByNameExact with client sort strips sort and matches the exact target entity"
        resExactWithSort.content.size() == 1
        resExactWithSort.content[0].id() == org1Id

        and: "searchByLocation returns matching organization across null, unpaged, and sorted pageables"
        !resLocNull.content.isEmpty()
        resLocNull.content[0].id() == org1Id
        !resLocUnpaged.content.isEmpty()
        resLocUnpaged.content[0].id() == org1Id
        !resLocSorted.content.isEmpty()
        resLocSorted.content[0].id() == org1Id

        and: "searchByRegion returns matching organization across null, unpaged, and sorted pageables"
        !resRegionNull.content.isEmpty()
        resRegionNull.content[0].id() == org1Id
        !resRegionUnpaged.content.isEmpty()
        resRegionUnpaged.content[0].id() == org1Id
        !resRegionSorted.content.isEmpty()
        resRegionSorted.content[0].id() == org1Id
    }

    /********** AUTHORIZATION & SECURITY Tests **********/

    def "SECURITY | should reject unauthenticated GET /organizations with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to list organizations"
        client.exchange(HttpRequest.GET("/organizations"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "SECURITY | should reject unauthenticated POST /organizations with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to create an organization"
        def command = new CreateOrganizationCommand("Unauth Org", "https://example.com", null, true)
        client.exchange(HttpRequest.POST("/organizations", command), Organization)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "SECURITY | should reject unauthenticated PUT /organizations/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to update an organization"
        def command = new UpdateOrganizationCommand("Unauth Org", "https://example.com", null, true)
        client.exchange(HttpRequest.PUT("/organizations/${UUID.randomUUID()}", command), Organization)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "SECURITY | should reject unauthenticated DELETE /organizations/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to delete an organization"
        client.exchange(HttpRequest.DELETE("/organizations/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "SECURITY | should return 403 FORBIDDEN when standard user without system admin claim accesses GET /organizations"() {
        given: "a standard user"
        def standardUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", standardUserId, "std-${UUID.randomUUID()}@example.com".toString())
        createdUserIds.add(standardUserId)

        when: "standard user attempts GET /organizations"
        client.exchange(authenticated(HttpRequest.GET("/organizations"), standardUserId.toString(), ["STANDARD_USER"]))

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "SECURITY | should return 403 FORBIDDEN when standard user without org admin authority updates organization"() {
        given: "an organization and a standard unauthorized user"
        def org = organizationRepository.save(new Organization(null, "Auth Org ${faker.company().name()}", "https://example.com", null, true, UNVERIFIED, null, []))
        def unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unauth-${UUID.randomUUID()}@example.com".toString())
        createdUserIds.add(unauthUserId)

        def updated = new UpdateOrganizationCommand("Hacked Name", "https://hacked.com", null, true)

        when: "unauthorized user attempts to update the organization"
        client.exchange(authenticated(HttpRequest.PUT("/organizations/${org.id()}", updated), unauthUserId.toString(), ["STANDARD_USER"]), Organization)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "SECURITY | should return 403 FORBIDDEN when standard user without org admin authority deletes organization"() {
        given: "an organization and a standard unauthorized user"
        def org = organizationRepository.save(new Organization(null, "Delete Auth Org ${faker.company().name()}", "https://example.com", null, true, UNVERIFIED, null, []))
        def unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unauth-del-${UUID.randomUUID()}@example.com".toString())
        createdUserIds.add(unauthUserId)

        when: "unauthorized user attempts to delete the organization"
        client.exchange(authenticated(HttpRequest.DELETE("/organizations/${org.id()}"), unauthUserId.toString(), ["STANDARD_USER"]))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "AUTHORIZATION | should allow organization admin to update and delete organization"() {
        given: "an organization and an ORG_ADMIN for that organization"
        def org = organizationRepository.save(new Organization(null, "Allowed Org ${faker.company().name()}", "https://example.com", null, true, UNVERIFIED, null, []))
        def adminUser = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", adminUser, "admin-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_ADMIN')", adminUser, org.id())
        createdUserIds.add(adminUser)

        def updated = new UpdateOrganizationCommand("Updated By Admin", "https://example.com", null, true)

        when: "org admin updates the organization via HTTP PUT"
        def response = client.exchange(authenticated(HttpRequest.PUT("/organizations/${org.id()}", updated), adminUser.toString(), ["STANDARD_USER"]), Organization)

        then: "it succeeds"
        response.status == HttpStatus.OK
        response.body().name() == "Updated By Admin"

        when: "org admin deletes the organization via HTTP DELETE"
        def delResponse = client.exchange(authenticated(HttpRequest.DELETE("/organizations/${org.id()}"), adminUser.toString(), ["STANDARD_USER"]))

        then: "it is removed"
        delResponse.status == HttpStatus.OK
        !organizationRepository.findById(org.id()).isPresent()
    }
}
