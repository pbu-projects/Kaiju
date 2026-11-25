package lol.pbu.kaiju.controller

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
import lol.pbu.kaiju.dto.CreateOrganizationRequest
import lol.pbu.kaiju.dto.UpdateOrganizationRequest
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.OrganizationRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import spock.lang.Shared
import spock.lang.Unroll

import java.util.UUID

import static lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED

@MicronautTest(transactional = false)
class OrganizationControllerSpec extends BaseControllerSpec {

    @Inject
    OrganizationRepository organizationRepository

    @Inject
    OrganizationController organizationController

    @Shared
    Faker faker = new Faker()

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
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map)
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
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map)
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
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map)
        toPage(response.body())
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid organization"() {
        given: "a new valid organization request"
        def request = new CreateOrganizationRequest(
                "Test Org ${faker.company().name()}",
                "https://${faker.internet().domainName()}",
                null,
                true
        )

        when: "the organization is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/organizations", request)), Organization)
        Organization saved = track(response.body())

        then: "the organization is persisted with a generated ID"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id() != null
            saved.name() == request.name()
            saved.websiteUrl() == request.websiteUrl()
        }

        and: "it can be retrieved from the database"
        def result = organizationRepository.findById(saved.id()).orElse(null)
        verifyAll(result) {
            result != null
            id() == saved.id()
            name() == request.name()
            websiteUrl() == request.websiteUrl()
        }
    }

    @Unroll
    def "CREATE | should fail to save organization with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add an organization with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/organizations", payload)), Organization)

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
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organizations/${id}")), Organization)

        then: "the correct organization is returned"
        verifyAll(response) {
            status == HttpStatus.OK
            body().id() == id
            body().name() == "Test Organization Read"
        }
    }

    def "READ | should return 404 for a non-existent organization ID"() {
        when: "a non-existent organization is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/organizations/${UUID.randomUUID()}")), Organization)

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
        def updateRequest = new UpdateOrganizationRequest(newName, newUrl, null, true)

        when: "the organization is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/organizations/${id}", updateRequest)), Organization)
        Organization updated = response.body()

        then: "the returned organization contains the updated data"
        verifyAll {
            response.status == HttpStatus.OK
            updated.id() == id
            updated.name() == newName
            updated.websiteUrl() == newUrl
        }

        and: "the changes are persisted in the database"
        def dbResult = organizationRepository.findById(id).orElse(null)
        verifyAll(dbResult) {
            dbResult != null
            name() == newName
            websiteUrl() == newUrl
        }
    }

    def "UPDATE | should fail to update a non-existent organization"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new UpdateOrganizationRequest("Test Org", "https://example.com", null, true)

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/organizations/${nonExistentId}", updateRequest)), Organization)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    @Unroll
    def "UPDATE | should fail to update organization with invalid data: #testCase"(String testCase, Map payload) {
        given: "an existing organization"
        def org = organizationRepository.save(new Organization(null, "Valid Org For Update", "https://example.com", null, true, UNVERIFIED, null, []))

        when: "an update with invalid data is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/organizations/${org.id()}", payload)), Organization)

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
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/organizations/${id}", payload)), Organization)
        Organization updated = response.body()

        then: "the safe fields are updated correctly and sensitive fields are NOT updated"
        verifyAll(updated) {
            name() == (shouldNameChange ? payload.name : "Original Name")
            verificationStatus() == expectedStatus
        }

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
        def request = new CreateOrganizationRequest(
                "Temporary Org to Delete",
                "https://example.org",
                null,
                true
        )
        def createResponse = client.exchange(asGlobalAdmin(HttpRequest.POST("/organizations", request)), Organization)
        UUID id = createResponse.body().id()
        assert organizationRepository.existsById(id)

        when: "the organization is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/organizations/${id}")))

        then: "the organization no longer exists in the repository or database"
        verifyAll {
            response.status == HttpStatus.OK
            !organizationRepository.findById(id).isPresent()
            !organizationRepository.existsById(id)
        }
    }

    def "DELETE | should fail to delete a non-existent organization"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/organizations/${nonExistentId}")))

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** LIST Tests **********/

    def "LIST | should fully drain all organizations sequentially using cursors"() {
        setup:
        (1..3).each { i ->
            dataBuilder.organization().withName("Drain Test Org ${i}").asPublic().verified().persist()
        }
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
        def totalCount = organizationRepository.count()
        verifyAll {
            allOrgs.size() == totalCount
            allOrgs.size() >= 2
        }
    }

    def "LIST | should retrieve organizations with pagination via HTTP GET"() {
        setup:
        (1..3).each { i ->
            dataBuilder.organization().withName("Page Test Org ${i}").asPublic().verified().persist()
        }

        when: "requesting organizations via HTTP GET as global admin"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organizations?size=5")), Map)

        then: "the response is 200 OK with content list"
        Map body = response.body()
        verifyAll {
            response.status == HttpStatus.OK
            body.content instanceof List
            body.content.size() >= 2
        }
    }

    def "LIST | should retrieve organizations with pagination and sorting via HTTP GET"() {
        setup:
        (1..3).each { i ->
            dataBuilder.organization().withName("Sort Test Org ${i}").asPublic().verified().persist()
        }

        when: "requesting organizations via HTTP GET as global admin with sort"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/organizations?size=5&sort=name,asc")), Map)

        then: "the response is 200 OK with content list"
        Map body = response.body()
        verifyAll {
            response.status == HttpStatus.OK
            body.content instanceof List
            body.content.size() >= 2
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
            dataBuilder.organization().withName(orgName).asPublic().verified().persist()
        }

        when: "searching with double quotes for exact parent name"
        PageResponse<Organization> results = searchByName('"The Salvation Army"', false, Pageable.from(0, 10))

        then: "only the exact match is returned"
        verifyAll {
            results.content.size() == 1
            results.content[0].name == "The Salvation Army"
        }
    }

    def "SEARCH BY NAME | exact search with single quotes should return strictly the exact organization"() {
        given: "a cluster of organizations with similar names"
        def names = [
                "The Salvation Army",
                "The Salvation Army - Denver Citadel Corps",
                "Salvation Army Family Store"
        ]
        names.each { orgName ->
            dataBuilder.organization().withName(orgName).asPublic().verified().persist()
        }

        when: "searching with single quotes for exact name"
        PageResponse<Organization> results = searchByName("'The Salvation Army'", false, Pageable.from(0, 10))

        then: "only the exact match is returned"
        verifyAll {
            results.content.size() == 1
            results.content[0].name == "The Salvation Army"
        }
    }

    def "SEARCH BY NAME | exact search with exact=true parameter should return strictly the exact organization"() {
        given: "organizations with similar names"
        def names = [
                "The Salvation Army",
                "The Salvation Army - Denver Citadel Corps",
                "Salvation Army Family Store"
        ]
        names.each { orgName ->
            dataBuilder.organization().withName(orgName).asPublic().verified().persist()
        }

        when: "searching with exact=true flag"
        PageResponse<Organization> results = searchByName("The Salvation Army", true, Pageable.from(0, 10))

        then: "only the exact match is returned"
        verifyAll {
            results.content.size() == 1
            results.content[0].name == "The Salvation Army"
        }
    }

    def "SEARCH BY NAME | case-insensitive exact search should match regardless of casing"() {
        given: "an organization with mixed case"
        dataBuilder.organization().withName("The Salvation Army").asPublic().verified().persist()
        dataBuilder.organization().withName("The Salvation Army - Denver Citadel Corps").asPublic().verified().persist()

        when: "searching with lowercase quoted string"
        PageResponse<Organization> results = searchByName('"the salvation army"', false, Pageable.from(0, 10))

        then: "it matches the organization case-insensitively"
        verifyAll {
            results.content.size() == 1
            results.content[0].name == "The Salvation Army"
        }
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
            dataBuilder.organization().withName(orgName).asPublic().verified().persist()
        }

        when: "performing a non-exact ranked search"
        PageResponse<Organization> results = searchByName("Salvation Army", false, Pageable.from(0, 20))

        then: "all 7 Salvation Army organizations are returned, ranked at the very top"
        verifyAll(results) {
            content.size() == 7
            content.every { it.name().contains("Salvation Army") }
            content[0].name() == "The Salvation Army"
        }
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
            dataBuilder.organization().withName(orgName).asPublic().verified().persist()
        }

        when: "searching for branch prefix"
        PageResponse<Organization> results = searchByName("The Salvation Army -", false, Pageable.from(0, 10))

        then: "only the two Corps branches are returned"
        verifyAll {
            results.content.size() == 2
            results.content.collect { it.name() }.sort() == [
                    "The Salvation Army - Aurora Corps Community Center",
                    "The Salvation Army - Denver Citadel Corps"
            ]
        }
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
        def locNear = dataBuilder.location()
                .withName("Loc Near")
                .withAddressLine("123 St")
                .withCity("Denver")
                .withCountryCode("US")
                .atPoint(-104.9903, 39.7572)
                .persist()
        def orgNear = dataBuilder.organization()
                .withName("Org Near Denver")
                .asPublic()
                .verified()
                .withLocation(locNear)
                .persist()
        def orgNearId = orgNear.id()

        def locMid = dataBuilder.location()
                .withName("Loc Mid")
                .withAddressLine("456 St")
                .withCity("Denver")
                .withCountryCode("US")
                .atPoint(-104.9903, 39.8292)
                .persist()
        def orgMid = dataBuilder.organization()
                .withName("Org Mid Distance")
                .asPublic()
                .verified()
                .withLocation(locMid)
                .persist()
        def orgMidId = orgMid.id()

        def locFar = dataBuilder.location()
                .withName("Loc Far")
                .withAddressLine("789 St")
                .withCity("Boulder")
                .withCountryCode("US")
                .atPoint(-105.2705, 40.0150)
                .persist()
        dataBuilder.organization()
                .withName("Org Far Boulder")
                .asPublic()
                .verified()
                .withLocation(locFar)
                .persist()

        when: "searching within 15 km of Denver center"
        PageResponse<Organization> results = searchByLocation(-104.9903, 39.7392, 15000, Pageable.from(0, 10))

        then: "Near and Mid orgs are returned, Far is excluded, ordered by distance"
        verifyAll {
            results.content.size() == 2
            results.content[0].id() == orgNearId
            results.content[1].id() == orgMidId
        }
    }

    def "SEARCH BY NAME | fuzzy trigram search should tolerate typos like 'Slavation Army'"() {
        given: "a cluster of Salvation Army organizations and an unrelated org"
        def parent = dataBuilder.organization().withName("The Salvation Army").asPublic().verified().persist()
        def parentId = parent.id()
        dataBuilder.organization().withName("The Salvation Army - Denver Citadel Corps").asPublic().verified().persist()
        dataBuilder.organization().withName("Salvation Army Family Store").asPublic().verified().persist()
        dataBuilder.organization().withName("Red Cross Society").asPublic().verified().persist()

        when: "searching with a typo: 'Slavation Army'"
        PageResponse<Organization> results = searchByName("Slavation Army", false, Pageable.from(0, 10))

        then: "The Salvation Army is matched via trigram similarity and ranked first"
        verifyAll {
            !results.content.isEmpty()
            results.content[0].id() == parentId
            results.content[0].name() == "The Salvation Army"
            results.content.every { it.name().contains("Salvation Army") }
        }
    }

    def "SEARCH BY REGION | should return organizations operating within the specified administrative region"() {
        given: "two administrative regions"
        def region1 = dataBuilder.region().withName("Colorado Region").persist()
        def region2 = dataBuilder.region().withName("Utah Region").withGeometry(
                dataBuilder.geometryFactory.createPolygon([
                        new Coordinate(-114, 37),
                        new Coordinate(-109, 37),
                        new Coordinate(-109, 42),
                        new Coordinate(-114, 42),
                        new Coordinate(-114, 37)
                ] as Coordinate[])
        ).persist()
        def region1Id = region1.id()
        def region2Id = region2.id()

        and: "organizations assigned to different regions"
        def org1 = dataBuilder.organization().withName("Colorado Food Bank").asPublic().verified().persist()
        def org2 = dataBuilder.organization().withName("Utah Food Bank").asPublic().verified().persist()
        def org1Id = org1.id()
        def org2Id = org2.id()

        dataBuilder.linkOrganizationToRegion(org1Id, region1Id)
        dataBuilder.linkOrganizationToRegion(org2Id, region2Id)

        when: "searching for organizations in Region 1"
        PageResponse<Organization> results = searchByRegion(region1Id, Pageable.from(0, 10))

        then: "only the organization operating in Region 1 is returned"
        verifyAll {
            results.content.size() == 1
            results.content[0].id() == org1Id
            results.content[0].name() == "Colorado Food Bank"
        }
    }

    @Unroll
    def "SEARCH SECURITY | hides private organizations from #searchType search"() {
        given: "a private organization and a public organization with similar names"
        def publicOrg = dataBuilder.organization().withName("Confidential Shelter Public").asPublic().verified().persist()
        def privateOrg = dataBuilder.organization().withName("Confidential Shelter Private").asPrivate().verified().persist()
        def publicOrgId = publicOrg.id()
        def privateOrgId = privateOrg.id()

        and: "locations and regions assigned to both"
        def locPublic = dataBuilder.location().withName("Loc Pub").withAddressLine("1 St").withCity("City").withCountryCode("US").atPoint(-104.99, 39.74).persist()
        def locPrivate = dataBuilder.location().withName("Loc Priv").withAddressLine("2 St").withCity("City").withCountryCode("US").atPoint(-104.99, 39.74).persist()
        dataBuilder.linkOrganizationToLocation(publicOrgId, locPublic.id())
        dataBuilder.linkOrganizationToLocation(privateOrgId, locPrivate.id())

        def region = dataBuilder.region().withName("Test Region Private").persist()
        def regionId = region.id()
        dataBuilder.linkOrganizationToRegion(publicOrgId, regionId)
        dataBuilder.linkOrganizationToRegion(privateOrgId, regionId)

        when: "searching by #searchType"
        PageResponse<Organization> results
        switch (searchType) {
            case "name":
                results = searchByName("Confidential Shelter", false, Pageable.from(0, 10))
                break
            case "location":
                results = searchByLocation(-104.99, 39.74, 5000, Pageable.from(0, 10))
                break
            case "region":
                results = searchByRegion(regionId, Pageable.from(0, 10))
                break
        }

        then: "private organization is never returned"
        results.content.collect { it.id() } == [publicOrgId]

        where:
        searchType << ["name", "location", "region"]
    }

    def "SEARCH SECURITY | should NOT return unverified, suspended, or revoked organizations"() {
        given: "organizations in various verification statuses"
        def verified = dataBuilder.organization().withName("Charity Org Verified").asPublic().verified().persist()
        dataBuilder.organization().withName("Charity Org Unverified").asPublic().unverified().persist()
        dataBuilder.organization().withName("Charity Org Suspended").asPublic().withVerificationStatus(VerificationStatus.SUSPENDED).persist()
        dataBuilder.organization().withName("Charity Org Revoked").asPublic().withVerificationStatus(VerificationStatus.REVOKED).persist()

        def verifiedId = verified.id()

        when: "searching by name"
        def results = searchByName("Charity Org", false, Pageable.from(0, 10))

        then: "only the verified organization is returned"
        results.content.collect { it.id() } == [verifiedId]
    }

    @Unroll
    def "SEARCH SECURITY | wildcard or empty query '#query' returns empty results"() {
        given: "some organizations exist in the database"
        dataBuilder.organization().withName("Wildcard Test Org A").asPublic().verified().persist()
        dataBuilder.organization().withName("Wildcard Test Org B").asPublic().verified().persist()

        when: "searching with '#query'"
        def results = searchByName(query, false, Pageable.from(0, 10))

        then: "an empty page is returned"
        results.content.isEmpty()

        where:
        query << ["%", "_", " %_% ", '""']
    }

    /********** EXHAUSTIVE QUERY VARIATIONS & EDGE CASES **********/

    @Unroll
    def "SEARCH BY NAME | quoted query '#query' with exact=#exact returns only the exact org"() {
        given: "an organization and a branch organization in database"
        def org = dataBuilder.organization().withName("The Salvation Army").asPublic().verified().persist()
        def orgId = org.id()
        dataBuilder.organization().withName("The Salvation Army - Denver Citadel Corps").asPublic().verified().persist()

        when: "searching with '#query' and exact=#exact"
        def results = searchByName(query, exact, Pageable.from(0, 10))

        then: "only the exact parent organization is returned"
        verifyAll {
            results.content.size() == 1
            results.content[0].id() == orgId
        }

        where:
        query                       | exact
        '  "The Salvation Army"  '  | false
        '"  The Salvation Army  "'  | false
        '"The Salvation Army"'      | true
    }

    @Unroll
    def "SEARCH BY NAME | quote-only or empty quote query '#query' returns empty results"() {
        given: "organizations exist in database"
        dataBuilder.organization().withName("The Salvation Army").asPublic().verified().persist()
        dataBuilder.organization().withName("The Salvation Army - Denver Citadel Corps").asPublic().verified().persist()

        when: "searching with '#query'"
        def results = searchByName(query, false, Pageable.from(0, 10))

        then: "an empty page is returned"
        results.content.isEmpty()

        where:
        query << ["'", '"', '"   "', "'   '"]
    }

    @Unroll
    def "SEARCH BY NAME | mismatched quote query '#query' falls back to ranked search"() {
        given: "an organization and a branch organization in database"
        def org = dataBuilder.organization().withName("The Salvation Army").asPublic().verified().persist()
        def orgId = org.id()
        dataBuilder.organization().withName("The Salvation Army - Denver Citadel Corps").asPublic().verified().persist()

        when: "searching with mismatched quotes '#query'"
        def results = searchByName(query, false, Pageable.from(0, 10))

        then: "ranked search surfaces the parent organization first"
        verifyAll {
            !results.content.isEmpty()
            results.content[0].id() == orgId
        }

        where:
        query << ['"The Salvation Army', 'The Salvation Army"', "'The Salvation Army", "The Salvation Army'"]
    }

    @Unroll
    def "SEARCH BY NAME | typo query '#query' still finds The Salvation Army first"() {
        given: "Salvation Army parent organization"
        def parent = dataBuilder.organization().withName("The Salvation Army").asPublic().verified().persist()
        def parentId = parent.id()
        dataBuilder.organization().withName("The Salvation Army - Denver Citadel Corps").asPublic().verified().persist()

        when: "searching with misspelled query '#query'"
        def results = searchByName(query, false, Pageable.from(0, 10))

        then: "The Salvation Army is ranked first"
        verifyAll {
            !results.content.isEmpty()
            results.content[0].id() == parentId
        }

        where:
        query << ["Salavtion Army", "Salvation Amry", "The Slavation Army"]
    }

    @Unroll
    def "SEARCH BY NAME | query '#query' ranks '#canonicalName' above competitor"() {
        given: "a canonical organization with a leading article and a competitor without it"
        def canonical = dataBuilder.organization().withName(canonicalName).asPublic().verified().persist()
        def competitor = dataBuilder.organization().withName(competitorName).asPublic().verified().persist()

        when: "searching without the leading article"
        def results = searchByName(query, false, Pageable.from(0, 10))

        then: "the canonical organization ranks above the competitor"
        verifyAll {
            results.content.size() == 2
            results.content[0].id() == canonical.id()
            results.content[1].id() == competitor.id()
        }

        where:
        query                        | canonicalName                  | competitorName
        "Organization Example"       | "An Organization Example"      | "Organization Example Regional Branch"
        "Better Tomorrow Foundation" | "A Better Tomorrow Foundation" | "Better Tomorrow Foundation Network"
    }

    @Unroll
    def "SEARCH BY NAME | literal wildcard query '#query' matches only '#expectedName'"() {
        given: "organizations with literal % and _ in their names"
        def orgPercent = dataBuilder.organization().withName("100% Volunteer Initiative").asPublic().verified().persist()
        def orgUnderscore = dataBuilder.organization().withName("Special_Ops Outreach").asPublic().verified().persist()
        dataBuilder.organization().withName("SpecialXOps Outreach").asPublic().verified().persist()

        def expectedId = expectedName == "100% Volunteer Initiative" ? orgPercent.id() : orgUnderscore.id()

        when: "searching for '#query'"
        def results = searchByName(query, false, Pageable.from(0, 10))

        then: "only the matching organization is returned"
        verifyAll {
            results.content.size() == 1
            results.content[0].id() == expectedId
        }

        where:
        query         | expectedName
        "100%"        | "100% Volunteer Initiative"
        "Special_Ops" | "Special_Ops Outreach"
    }

    @Unroll
    def "SEARCH BY NAME | query '#query' ranks '#expectedName' first"() {
        given: "a cluster of Salvation Army organizations"
        Map<String, UUID> orgIdsByName = [
                "The Salvation Army",
                "The Salvation Army - Denver Citadel Corps",
                "The Salvation Army - Aurora Corps Community Center",
                "The Salvation Army - Colorado Springs Corps",
                "The Salvation Army Intermountain Division",
                "Salvation Army Family Store",
                "Salvation Army Emergency Disaster Services",
                "Friends of The Salvation Army"
        ].collectEntries { name ->
            [(name): dataBuilder.organization().withName(name).asPublic().verified().persist().id()]
        }

        when: "searching for '#query'"
        def results = searchByName(query, false, Pageable.from(0, 10))

        then: "the target organization ranks at the top of results"
        verifyAll {
            !results.content.isEmpty()
            results.content[0].id() == orgIdsByName[expectedName]
        }

        where:
        query                    | expectedName
        "Salvation Army"         | "The Salvation Army"
        "Denver Citadel"         | "The Salvation Army - Denver Citadel Corps"
        "Aurora"                 | "The Salvation Army - Aurora Corps Community Center"
        "Colorado Springs Corps" | "The Salvation Army - Colorado Springs Corps"
        "Intermountain"          | "The Salvation Army Intermountain Division"
        "Family Store"           | "Salvation Army Family Store"
        "Emergency Disaster"     | "Salvation Army Emergency Disaster Services"
        "Friends of"             | "Friends of The Salvation Army"
    }

    private Map persistPagingFixtures() {
        def loc = dataBuilder.location()
                .withName("Paging Test Loc")
                .withAddressLine("100 Main St")
                .withCity("Denver")
                .withCountryCode("US")
                .atPoint(-104.99, 39.74)
                .persist()

        def org1 = dataBuilder.organization()
                .withName("Paging Test Org 1")
                .asPublic()
                .verified()
                .withLocation(loc)
                .persist()
        dataBuilder.organization()
                .withName("Paging Test Org 2")
                .asPublic()
                .verified()
                .persist()

        def region = dataBuilder.region().withName("Paging Region").persist()
        dataBuilder.linkOrganizationToRegion(org1.id(), region.id())

        return [org1Id: org1.id(), regionId: region.id()]
    }

    @Unroll
    def "PAGINATION & SORTING | getOrganizations handles #caseName pageable"() {
        given: "organizations exist"
        persistPagingFixtures()

        when: "calling getOrganizations with #caseName pageable"
        def result = organizationController.getOrganizations(pageable)

        then: "organizations are returned"
        !result.content.isEmpty()

        where:
        caseName  | pageable
        "null"    | null
        "unpaged" | CursoredPageable.from(Sort.of(Sort.Order.asc("name"))).withoutPaging()
    }

    @Unroll
    def "PAGINATION & SORTING | searchByName normalizes #caseName pageable"() {
        given: "matching organizations exist"
        persistPagingFixtures()

        when: "calling searchByName with #caseName pageable"
        def result = organizationController.searchByName("Paging Test Org", false, pageable)

        then: "matching organizations are returned with normalized page size"
        verifyAll(result) {
            !content.isEmpty()
            result.pageable.size == expectedSize
        }

        where:
        caseName    | pageable              | expectedSize
        "null"      | null                  | 20
        "unpaged"   | Pageable.unpaged()    | 20
        "oversized" | Pageable.from(0, 500) | 100
    }

    def "PAGINATION & SORTING | searchByName strips client DESC sort to keep native relevance order"() {
        given: "matching organizations exist"
        persistPagingFixtures()
        def sortedPageable = Pageable.from(0, 10, Sort.of(Sort.Order.desc("name")))

        when: "calling searchByName with client DESC sort"
        def result = organizationController.searchByName("Paging Test Org", false, sortedPageable)

        then: "native relevance ASC tie-breaking is preserved"
        result.content*.name() == ['Paging Test Org 1', 'Paging Test Org 2']
    }

    def "PAGINATION & SORTING | searchByName exact match strips client sort and isolates target entity"() {
        given: "matching organizations exist"
        def fixtures = persistPagingFixtures()
        def sortedPageable = Pageable.from(0, 10, Sort.of(Sort.Order.desc("name")))

        when: "calling exact searchByName with client sort"
        def result = organizationController.searchByName('"Paging Test Org 1"', false, sortedPageable)

        then: "only the exact matching organization is returned"
        verifyAll(result) {
            content.size() == 1
            content[0].id() == fixtures.org1Id
        }
    }

    @Unroll
    def "PAGINATION & SORTING | searchByLocation handles #caseName pageable"() {
        given: "an organization at the search coordinates"
        def fixtures = persistPagingFixtures()

        when: "calling searchByLocation with #caseName pageable"
        def result = organizationController.searchByLocation(-104.99, 39.74, 50000, pageable)

        then: "the matching organization is returned"
        verifyAll(result) {
            !content.isEmpty()
            content[0].id() == fixtures.org1Id
        }

        where:
        caseName  | pageable
        "null"    | null
        "unpaged" | Pageable.unpaged()
        "sorted"  | Pageable.from(0, 10, Sort.of(Sort.Order.desc("name")))
    }

    @Unroll
    def "PAGINATION & SORTING | searchByRegion handles #caseName pageable"() {
        given: "an organization linked to a region"
        def fixtures = persistPagingFixtures()

        when: "calling searchByRegion with #caseName pageable"
        def result = organizationController.searchByRegion(fixtures.regionId, pageable)

        then: "the matching organization is returned"
        verifyAll(result) {
            !content.isEmpty()
            content[0].id() == fixtures.org1Id
        }

        where:
        caseName  | pageable
        "null"    | null
        "unpaged" | Pageable.unpaged()
        "sorted"  | Pageable.from(0, 10, Sort.of(Sort.Order.desc("name")))
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
        def request = new CreateOrganizationRequest("Unauth Org", "https://example.com", null, true)
        client.exchange(HttpRequest.POST("/organizations", request), Organization)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "SECURITY | should reject unauthenticated PUT /organizations/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to update an organization"
        def request = new UpdateOrganizationRequest("Unauth Org", "https://example.com", null, true)
        client.exchange(HttpRequest.PUT("/organizations/${UUID.randomUUID()}", request), Organization)

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
        when: "standard user attempts GET /organizations"
        client.exchange(asVolunteer(HttpRequest.GET("/organizations"), TestPersonas.VICTOR_VOLUNTEER))

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "SECURITY | should return 403 FORBIDDEN when standard user without org admin authority updates organization"() {
        given: "an organization and a standard unauthorized user"
        def org = dataBuilder.organization().withName("Auth Org ${faker.company().name()}").withWebsiteUrl("https://example.com").unverified().persist()
        def updated = new UpdateOrganizationRequest("Hacked Name", "https://hacked.com", null, true)

        when: "unauthorized user attempts to update the organization"
        client.exchange(asVolunteer(HttpRequest.PUT("/organizations/${org.id()}", updated), TestPersonas.VICTOR_VOLUNTEER), Organization)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "SECURITY | should return 403 FORBIDDEN when standard user without org admin authority deletes organization"() {
        given: "an organization and a standard unauthorized user"
        def org = dataBuilder.organization().withName("Delete Auth Org ${faker.company().name()}").withWebsiteUrl("https://example.com").unverified().persist()

        when: "unauthorized user attempts to delete the organization"
        client.exchange(asVolunteer(HttpRequest.DELETE("/organizations/${org.id()}"), TestPersonas.VICTOR_VOLUNTEER))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "AUTHORIZATION | allows organization admin to update their organization"() {
        given: "an organization and an ORG_ADMIN for that organization"
        def org = dataBuilder.organization().withName("Allowed Org ${faker.company().name()}").withWebsiteUrl("https://example.com").unverified().persist()
        dataBuilder.organizationUser().asOrgAdmin(TestPersonas.LEO_LOCAL_ADMIN.id, org.id()).persist()

        def updated = new UpdateOrganizationRequest("Updated By Admin", "https://example.com", null, true)

        when: "org admin updates the organization via HTTP PUT"
        def response = client.exchange(asOrgAdmin(HttpRequest.PUT("/organizations/${org.id()}", updated), TestPersonas.LEO_LOCAL_ADMIN), Organization)

        then: "it succeeds"
        verifyAll(response) {
            status == HttpStatus.OK
            body().name() == "Updated By Admin"
        }
    }

    def "AUTHORIZATION | allows organization admin to delete their organization"() {
        given: "an organization and an ORG_ADMIN for that organization"
        def org = dataBuilder.organization().withName("Allowed Org ${faker.company().name()}").withWebsiteUrl("https://example.com").unverified().persist()
        dataBuilder.organizationUser().asOrgAdmin(TestPersonas.LEO_LOCAL_ADMIN.id, org.id()).persist()

        when: "org admin deletes the organization via HTTP DELETE"
        def delResponse = client.exchange(asOrgAdmin(HttpRequest.DELETE("/organizations/${org.id()}"), TestPersonas.LEO_LOCAL_ADMIN))

        then: "it is removed"
        verifyAll {
            delResponse.status == HttpStatus.OK
            !organizationRepository.findById(org.id()).isPresent()
        }
    }
}
