package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.OrganizationUser
import lol.pbu.kaiju.domain.RegionUser
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.OrganizationUserRole
import lol.pbu.kaiju.model.RegionUserRole
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.model.VerificationStatus
import net.datafaker.Faker
import spock.lang.Specification

import java.time.OffsetDateTime
import java.util.UUID

class DataBuilderSpec extends Specification {

    private Faker faker = new Faker()

    def "OrganizationBuilder | should build valid organization with defaults"() {
        given: "an organization builder without overrides"
        def builder = new OrganizationBuilder(null, null, faker)

        when: "building the organization"
        Organization org = builder.build()

        then: "sensible defaults are applied"
        verifyAll(org) {
            id() == null
            name() != null
            websiteUrl() == "https://example.org"
            parentId() == null
            isPublic() == true
            verificationStatus() == VerificationStatus.VERIFIED
            verificationExpiresAt() == null
            locations() != null
            locations().isEmpty()
        }
    }

    def "OrganizationBuilder | should apply all custom configurations"() {
        given: "a builder with customized properties"
        UUID customId = UUID.randomUUID()
        UUID expectedParentId = UUID.randomUUID()
        Organization parentOrg = new Organization(expectedParentId, "Parent Org", "https://parent.org", null, true, VerificationStatus.VERIFIED, null, [])
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMonths(6)
        Location loc = new Location(UUID.randomUUID(), "Main HQ", "123 St", "Denver", "CO", "80202", "US", null, "America/Denver")
        UUID loc2Id = UUID.randomUUID()

        when: "configuring and building with parent organization and location objects"
        Organization org1 = new OrganizationBuilder(null, null, faker)
                .withId(customId)
                .withName("Custom Org")
                .withWebsiteUrl("https://custom.org")
                .withParent(parentOrg)
                .asPrivate()
                .unverified()
                .withVerificationExpiresAt(expiresAt)
                .withLocation(loc)
                .withLocation(loc2Id)
                .build()

        then: "all custom properties are set correctly"
        verifyAll(org1) {
            id() == customId
            name() == "Custom Org"
            websiteUrl() == "https://custom.org"
            parentId() == expectedParentId
            isPublic() == false
            verificationStatus() == VerificationStatus.UNVERIFIED
            verificationExpiresAt() == expiresAt
            locations().size() == 2
            locations()[0].id() == loc.id()
            locations()[1].id() == loc2Id
        }

        when: "configuring with parent ID, public flag, and verification status"
        Organization org2 = new OrganizationBuilder(null, null, faker)
                .withParent(expectedParentId)
                .asPublic()
                .withPublic(true)
                .verified()
                .withVerificationStatus(VerificationStatus.SUSPENDED)
                .build()

        then: "the properties match the secondary configuration"
        verifyAll(org2) {
            parentId() == expectedParentId
            isPublic() == true
            verificationStatus() == VerificationStatus.SUSPENDED
        }
    }

    def "OrganizationUserBuilder | should build organization users with various roles and personas"() {
        given: "a builder"
        def builder = new OrganizationUserBuilder(null)
        UUID orgId = UUID.randomUUID()
        Organization org = new Organization(orgId, "Test Org", "https://test.org", null, true, VerificationStatus.VERIFIED, null, [])
        User user = new User(UUID.randomUUID(), "user@test.org", UserRole.STANDARD_USER, OffsetDateTime.now())

        when: "building as org admin"
        OrganizationUser admin = builder.asOrgAdmin(TestPersonas.LEO_LOCAL_ADMIN, org).build()

        then: "role and IDs match"
        verifyAll(admin) {
            id().userId() == TestPersonas.LEO_LOCAL_ADMIN.id
            id().organizationId() == orgId
            role() == OrganizationUserRole.ORG_ADMIN
        }

        when: "building as org manager"
        OrganizationUser manager = new OrganizationUserBuilder(null)
                .asOrgManager(user, orgId)
                .build()

        then: "manager role is applied"
        verifyAll(manager) {
            id().userId() == user.id()
            id().organizationId() == orgId
            role() == OrganizationUserRole.ORG_MANAGER
        }

        when: "building as org member with direct setters"
        OrganizationUser member = new OrganizationUserBuilder(null)
                .withUser(user.id())
                .withOrganization(orgId)
                .withRole(OrganizationUserRole.ORG_MEMBER)
                .build()

        then: "member role is applied"
        verifyAll(member) {
            id().userId() == user.id()
            id().organizationId() == orgId
            role() == OrganizationUserRole.ORG_MEMBER
        }
    }

    def "RegionUserBuilder | should build region users with various roles and personas"() {
        given: "a builder and entities"
        UUID regionId = UUID.randomUUID()
        AdministrativeRegion region = new AdministrativeRegion(regionId, "Test Region", null, null)
        User user = new User(UUID.randomUUID(), "director@test.org", UserRole.STANDARD_USER, OffsetDateTime.now())

        when: "building as region director"
        RegionUser director = new RegionUserBuilder(null)
                .asRegionDirector(TestPersonas.DANIEL_COUNTY_DIRECTOR, region)
                .build()

        then: "director properties match"
        verifyAll(director) {
            id().userId() == TestPersonas.DANIEL_COUNTY_DIRECTOR.id
            id().regionId() == regionId
            role() == RegionUserRole.REGION_DIRECTOR
        }

        when: "building as region agent"
        RegionUser agent = new RegionUserBuilder(null)
                .asRegionAgent(user, regionId)
                .build()

        then: "agent properties match"
        verifyAll(agent) {
            id().userId() == user.id()
            id().regionId() == regionId
            role() == RegionUserRole.REGION_AGENT
        }

        when: "building with individual setters"
        RegionUser userAgent = new RegionUserBuilder(null)
                .withUser(user.id())
                .withRegion(regionId)
                .withRole(RegionUserRole.REGION_AGENT)
                .build()

        then: "direct role is applied"
        verifyAll(userAgent) {
            id().userId() == user.id()
            id().regionId() == regionId
            role() == RegionUserRole.REGION_AGENT
        }
    }

    def "KaijuDataBuilder | should initialize factory methods"() {
        given: "a data builder instance"
        def dataBuilder = new KaijuDataBuilder()

        expect:
        verifyAll {
            dataBuilder.organization() != null
            dataBuilder.organizationUser() != null
            dataBuilder.regionUser() != null
            dataBuilder.user() != null
            dataBuilder.region() != null
            dataBuilder.location() != null
            dataBuilder.tag() != null
            dataBuilder.boundary() != null
            dataBuilder.geometryFactory != null
            dataBuilder.faker != null
        }
    }
}
