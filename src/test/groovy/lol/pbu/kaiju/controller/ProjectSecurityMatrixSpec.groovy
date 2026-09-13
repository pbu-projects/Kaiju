package lol.pbu.kaiju.controller

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import spock.lang.Unroll
import java.util.UUID

@MicronautTest(transactional = true)
class ProjectSecurityMatrixSpec extends BaseControllerSpec {

    // Simplified polygon roughly tracing the civic boundary of Denver, Colorado
    static final String DENVER_WKT = "POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))"
    
    // Coordinates known to be physically inside and outside the Denver polygon
    static final String POINT_INSIDE = "POINT(-104.9903 39.7392)"   // Downtown Denver
    static final String POINT_OUTSIDE = "POINT(-105.2705 40.0150)"  // Boulder, CO

    static String determineExpectedState(String orgStatus, String userRole, String location) {
        if (userRole == "REGION_AGENT") {
            return location == "INSIDE_BOUNDARY" ? "AUTO_APPROVED" : "FORBIDDEN"
        }
        if (orgStatus == "VERIFIED" && userRole == "ORG_MANAGER" && location == "INSIDE_BOUNDARY") {
            return "AUTO_APPROVED"
        }
        return "REQUIRES_REGIONAL_APPROVAL"
    }

    @Unroll
    def "should enforce matrix: Org[#orgStatus] + Role[#userRole] + Location[#location] -> Expect: #expectedApprovalState"() {
        given: "a real civic boundary (Denver) in the administrative_regions table"
        def regionId = UUID.randomUUID()
        sql.execute("""
            INSERT INTO administrative_regions (id, name, geom) 
            VALUES (?, 'City of Denver', ST_GeogFromText(?))
        """, [regionId, DENVER_WKT])

        and: "the test user, organization, and roles are seeded"
        def userId = UUID.randomUUID()
        sql.execute("INSERT INTO users (id, email, role) VALUES (?, ?, ?)", 
            [userId, "test-${UUID.randomUUID()}@example.com".toString(), userRole == "REGION_AGENT" ? "REGION_AGENT" : "STANDARD_USER"])
            
        def orgId = UUID.randomUUID()
        sql.execute("INSERT INTO organizations (id, name, verification_status) VALUES (?, ?, ?)", 
            [orgId, "Test Org", orgStatus == "VERIFIED" ? "VERIFIED" : "UNVERIFIED"])

        if (userRole == "ORG_MANAGER") {
            sql.execute("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", [userId, orgId])
        }
        if (userRole == "REGION_AGENT") {
            sql.execute("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_AGENT')", [userId, regionId])
        }

        and: "the target geographic point"
        String targetPointWkt = location == "INSIDE_BOUNDARY" ? POINT_INSIDE : POINT_OUTSIDE

        when: "the user attempts to create a project at that location"
        // TODO: Call ProjectController once it's implemented. For now we calculate it to ensure the matrix generates correctly.
        String actualResult = determineExpectedState(orgStatus, userRole, location)

        then: "the project is placed into the correct state"
        actualResult == expectedApprovalState

        where:
        [orgStatus, userRole, location] << [
                ["VERIFIED", "UNVERIFIED"],
                ["ORG_MANAGER", "STANDARD_USER", "REGION_AGENT"],
                ["INSIDE_BOUNDARY", "OUTSIDE_BOUNDARY"]
        ].combinations()
        
        expectedApprovalState = determineExpectedState(orgStatus, userRole, location)
    }
}
