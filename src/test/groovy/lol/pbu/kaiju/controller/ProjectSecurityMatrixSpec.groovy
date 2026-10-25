package lol.pbu.kaiju.controller

import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.TestFixtures
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.security.ProjectSecurityService
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.locationtech.jts.io.WKTReader
import spock.lang.Unroll

import static lol.pbu.kaiju.model.ProjectStatus.ACTIVE
import static lol.pbu.kaiju.model.ProjectStatus.PENDING

@MicronautTest(transactional = false)
class ProjectSecurityMatrixSpec extends BaseControllerSpec {

    @Inject
    ProjectSecurityService projectSecurityService

    // Simplified polygon roughly tracing the civic boundary of Denver, Colorado
    static final String DENVER_WKT = "POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))"
    
    // Coordinates known to be physically inside and outside the Denver polygon
    static final String POINT_INSIDE = "POINT(-104.9903 39.7392)"   // Downtown Denver
    static final String POINT_OUTSIDE = "POINT(-105.2705 40.0150)"  // Boulder, CO

    // Ground truth rules for verification
    static ProjectStatus getGroundTruthExpectedState(String orgStatus, String userRole, String location) {
        if (orgStatus == "VERIFIED" && userRole == "ORG_MANAGER" && location == "INSIDE_BOUNDARY") {
            return ACTIVE
        }
        if (userRole == "REGION_AGENT") {
            return location == "INSIDE_BOUNDARY" ? ACTIVE : null // null means FORBIDDEN
        }
        return PENDING
    }

    @Unroll
    def "should enforce matrix: Org[#orgStatus] + Role[#userRole] + Location[#location] -> Expect: #expectedApprovalState"() {
        given: "a real civic boundary (Denver) in the administrative_regions table"
        Geometry denverGeom = new WKTReader().read(DENVER_WKT)
        def region = dataBuilder.region()
                .withName("City of Denver")
                .withGeometry(denverGeom)
                .persist()

        and: "the test user, organization, and roles are seeded"
        UserRole mappedUserRole = userRole == "REGION_AGENT" ? UserRole.REGION_AGENT : UserRole.STANDARD_USER
        def user = dataBuilder.user().withRole(mappedUserRole).persist()
        def userId = user.id()

        VerificationStatus mappedOrgStatus = orgStatus == "VERIFIED" ? VerificationStatus.VERIFIED : VerificationStatus.UNVERIFIED
        def org = dataBuilder.organization()
                .withName("Test Org")
                .withVerificationStatus(mappedOrgStatus)
                .persist()
        def orgId = org.id()

        // Link the org to the region so we can test the civic boundary logic
        dataBuilder.linkOrganizationToRegion(org, region)

        if (userRole == "ORG_MANAGER") {
            dataBuilder.organizationUser().asOrgManager(user, org).persist()
        }
        if (userRole == "REGION_AGENT") {
            dataBuilder.regionUser().asRegionAgent(user, region).persist()
        }

        and: "the target geographic point"
        String targetPointWkt = location == "INSIDE_BOUNDARY" ? POINT_INSIDE : POINT_OUTSIDE
        Geometry geom = new WKTReader().read(targetPointWkt)
        Location dummyLocation = TestFixtures.createDummyLocation((Point) geom)
        Organization dummyOrg = TestFixtures.createDummyOrganization(orgId, orgStatus)
        Project dummyProject = TestFixtures.createDummyProject(dummyOrg, dummyLocation)

        when: "the system evaluates the project creation request"
        def actualResult
        try {
            actualResult = projectSecurityService.evaluateProjectCreationByUser(userId, dummyProject)
        } catch (HttpStatusException e) {
            actualResult = null
        }

        then: "the project is placed into the correct state via PostGIS logic"
        actualResult == expectedApprovalState

        where:
        [orgStatus, userRole, location] << [
                ["VERIFIED", "UNVERIFIED"],
                ["ORG_MANAGER", "STANDARD_USER", "REGION_AGENT"],
                ["INSIDE_BOUNDARY", "OUTSIDE_BOUNDARY"]
        ].combinations()
        
        expectedApprovalState = ProjectSecurityMatrixSpec.getGroundTruthExpectedState(orgStatus, userRole, location)
    }
}
