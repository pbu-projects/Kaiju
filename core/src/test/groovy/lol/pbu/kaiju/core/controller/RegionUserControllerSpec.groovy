package lol.pbu.kaiju.core.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import lol.pbu.kaiju.core.domain.RegionUser
import lol.pbu.kaiju.core.domain.RegionUserId
import lol.pbu.kaiju.core.model.RegionUserRole
import lol.pbu.kaiju.core.repository.RegionUserRepository
import spock.lang.Unroll

class RegionUserControllerSpec extends BaseControllerSpec {

    @Inject
    RegionUserRepository regionUserRepository

    @Inject
    RegionUserController regionUserController

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid region user"() {
        given: "a new region user connection"
        UUID userId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "regionuser@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", regionId, "Test Region for User")

        def regionUserId = new RegionUserId(userId, regionId)
        def regionUser = new RegionUser(regionUserId, RegionUserRole.REGION_DIRECTOR)

        when: "the region user is added"
        RegionUser saved = regionUserController.addRegionUser(regionUser)

        then: "it can be retrieved"
        saved.id().userId() == userId
        saved.id().regionId() == regionId
        saved.role() == RegionUserRole.REGION_DIRECTOR

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", userId, regionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", regionId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    @Unroll
    def "CREATE | should fail to save region user with invalid data: #testCase"(String testCase, RegionUser regionUser) {
        when: "an attempt is made to add with invalid data"
        regionUserController.addRegionUser(regionUser)

        then: "an exception is thrown"
        thrown(Exception)

        where:
        testCase    | regionUser
        "Null Role" | new RegionUser(new RegionUserId(UUID.randomUUID(), UUID.randomUUID()), null)
        "Null ID"   | new RegionUser(null, RegionUserRole.REGION_DIRECTOR)
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing region user by composite ID"() {
        given: "an existing user and region"
        UUID userId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "regionuser-read@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", regionId, "Test Region for User Read")
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", userId, regionId)

        when: "the region user is requested"
        def result = regionUserController.getRegionUser(userId, regionId)

        then: "the correct record is returned"
        result.isPresent()
        result.get().id().userId() == userId
        result.get().id().regionId() == regionId
        result.get().role() == RegionUserRole.REGION_DIRECTOR

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", userId, regionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", regionId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    def "READ | should return empty for a non-existent composite ID"() {
        when: "a non-existent region user is requested"
        def result = regionUserController.getRegionUser(UUID.randomUUID(), UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing region user role"() {
        given: "an existing region user"
        UUID userId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "regionuser-update@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", regionId, "Test Region for User Update")
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_AGENT')", userId, regionId)

        def updateRequest = new RegionUser(null, RegionUserRole.REGION_DIRECTOR)

        when: "the region user is updated"
        RegionUser updated = regionUserController.updateRegionUser(userId, regionId, updateRequest)

        then: "the changes are reflected"
        updated.role() == RegionUserRole.REGION_DIRECTOR

        and: "persisted in the database"
        def role = sql.firstRow("SELECT role FROM region_users WHERE user_id = ? AND region_id = ?", [userId, regionId]).role
        role == 'REGION_DIRECTOR'

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", userId, regionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", regionId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    def "UPDATE | should fail to update a non-existent region user"() {
        given: "a non-existent composite ID and update request"
        def updateRequest = new RegionUser(null, RegionUserRole.REGION_DIRECTOR)

        when: "an update is attempted"
        regionUserController.updateRegionUser(UUID.randomUUID(), UUID.randomUUID(), updateRequest)

        then: "an exception is thrown"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should handle update of non-existent gracefully when using no-look"() {
        given: "a non-existent composite ID and update request"
        def updateRequest = new RegionUser(new RegionUserId(UUID.randomUUID(), UUID.randomUUID()), RegionUserRole.REGION_DIRECTOR)

        when: "a no-look update is attempted"
        regionUserController.updateRegionUserNoLook(UUID.randomUUID(), UUID.randomUUID(), updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing region user"() {
        given: "an existing region user"
        UUID userId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "regionuser-delete@example.com")
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, ?, ST_GeographyFromText('POLYGON((-105.0 39.0, -104.0 39.0, -104.0 40.0, -105.0 40.0, -105.0 39.0))'))", regionId, "Test Region for User Delete")
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_AGENT')", userId, regionId)

        when: "the region user is deleted"
        regionUserController.deleteRegionUser(userId, regionId)

        then: "it no longer exists"
        !regionUserRepository.existsById(new RegionUserId(userId, regionId))

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", userId, regionId)
        executeUpdate("DELETE FROM administrative_regions WHERE id = ?", regionId)
        executeUpdate("DELETE FROM users WHERE id = ?", userId)
    }

    /********** LIST Tests **********/

    def "LIST | should fully drain all region users sequentially using cursors"() {
        setup:
        Set<RegionUser> allRegionUsers = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("id.userId")))

        when: "iterating through pages"
        while (pageable != null) {
            CursoredPage<RegionUser> page = regionUserController.getRegionUsers(pageable)
            allRegionUsers.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected size matches the DB count"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM region_users").count
        allRegionUsers.size() == totalCount
    }
}
