package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.OrganizationUser
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.RegionUser
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.domain.Tag
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.dto.CreateProjectCommand
import lol.pbu.kaiju.dto.CreateShiftCommand
import lol.pbu.kaiju.dto.UpdateProjectCommand
import lol.pbu.kaiju.dto.UpdateShiftCommand
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.OrganizationUserRole
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
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

    def "ProjectBuilder | should build valid project with defaults"() {
        given: "a project builder without overrides"
        def builder = new ProjectBuilder(null, null, faker)

        when: "building the project"
        Project project = builder.build()

        then: "sensible defaults are applied including ACTIVE status and auto-created organization"
        verifyAll(project) {
            id == null
            organization != null
            organization.name != null
            managingRegion == null
            title != null
            title.startsWith("Project ")
            description != null
            description.length() >= 20
            projectType == ProjectType.STANDARD
            status == ProjectStatus.ACTIVE
            createdAt != null
            deletedAt == null
            deletedBy == null
            locations.isEmpty()
            boundaries.isEmpty()
        }
    }

    def "ProjectBuilder | should apply custom configurations and generate valid commands"() {
        given: "a project builder with customized properties"
        UUID projectId = UUID.randomUUID()
        Organization org = new Organization(UUID.randomUUID(), "Custom Org", "https://custom.org", null, true, VerificationStatus.VERIFIED, null, [])
        AdministrativeRegion region = new AdministrativeRegion(UUID.randomUUID(), "Custom Region", null, null)
        Location loc = new Location(UUID.randomUUID(), "Main Loc", "123 St", "Denver", "CO", "80202", "US", null, "America/Denver")
        OffsetDateTime created = OffsetDateTime.now()

        when: "configuring and building the project"
        Project project = new ProjectBuilder(null, null, faker)
                .withId(projectId)
                .forOrg(org)
                .inRegion(region)
                .withTitle("Custom Project")
                .withDescription("Custom description exceeding the required twenty characters minimum")
                .withType(ProjectType.OPEN_DOOR)
                .withStatus(ProjectStatus.PENDING)
                .withCreatedAt(created)
                .atLocation(loc)
                .build()

        then: "properties match configured values"
        verifyAll(project) {
            id == projectId
            organization.id() == org.id()
            managingRegion.id() == region.id()
            title == "Custom Project"
            description == "Custom description exceeding the required twenty characters minimum"
            projectType == ProjectType.OPEN_DOOR
            status == ProjectStatus.PENDING
            createdAt == created
            locations.size() == 1
            locations[0].id() == loc.id()
        }

        when: "generating create command"
        CreateProjectCommand createCmd = new ProjectBuilder(null, null, faker)
                .forOrg(org)
                .inRegion(region)
                .withTitle("Custom Project")
                .withDescription("Custom description exceeding the required twenty characters minimum")
                .withType(ProjectType.OPEN_DOOR)
                .withStatus(ProjectStatus.PENDING)
                .atLocation(loc)
                .asCommand()

        then: "command has valid attributes and default boundary"
        verifyAll(createCmd) {
            organizationId == org.id()
            managingRegionId == region.id()
            title == "Custom Project"
            description == "Custom description exceeding the required twenty characters minimum"
            projectType == ProjectType.OPEN_DOOR
            status == ProjectStatus.PENDING
            locations.size() == 1
            boundaries.size() == 1
            boundaries[0].coordinates().size() >= 3
        }

        when: "generating update command"
        UpdateProjectCommand updateCmd = new ProjectBuilder(null, null, faker)
                .forOrg(org)
                .inRegion(region)
                .withTitle("Updated Project")
                .withDescription("Updated description exceeding the required twenty characters minimum")
                .withType(ProjectType.REGIONAL)
                .asUpdateCommand()

        then: "update command matches attributes"
        verifyAll(updateCmd) {
            organizationId == org.id()
            managingRegionId == region.id()
            title == "Updated Project"
            description == "Updated description exceeding the required twenty characters minimum"
            projectType == ProjectType.REGIONAL
            boundaries.size() == 1
        }
    }

    def "ShiftBuilder | should build valid shift with defaults"() {
        given: "a shift builder without overrides"
        def builder = new ShiftBuilder(null, null, faker)

        when: "building the shift"
        Shift shift = builder.build()

        then: "sensible defaults are applied including 10 capacity and start/end times"
        verifyAll(shift) {
            id == null
            project != null
            !isVirtual
            location != null
            startTime != null
            endTime != null
            endTime.isAfter(startTime)
            tags.isEmpty()
        }
        builder.getCapacity() == 10
    }

    def "ShiftBuilder | should apply custom configurations and generate valid commands"() {
        given: "custom shift settings"
        UUID shiftId = UUID.randomUUID()
        UUID projId = UUID.randomUUID()
        Project project = new Project(projId, null, null, "Proj", "Desc", ProjectType.STANDARD, ProjectStatus.ACTIVE, OffsetDateTime.now(), null, null, [], [])
        Location loc = new Location(UUID.randomUUID(), "Loc", "123 St", "Denver", "CO", "80202", "US", null, "America/Denver")
        Tag tag = new Tag(UUID.randomUUID(), "Outdoor")
        OffsetDateTime start = OffsetDateTime.now().plusDays(2)
        OffsetDateTime end = start.plusHours(4)

        when: "configuring physical shift"
        Shift physicalShift = new ShiftBuilder(null, null, faker)
                .withId(shiftId)
                .forProject(project)
                .atLocation(loc)
                .withCapacity(25)
                .withTimes(start, end)
                .withTag(tag)
                .build()

        then: "physical shift properties match"
        verifyAll(physicalShift) {
            id == shiftId
            project.id() == projId
            !isVirtual
            location.id() == loc.id()
            startTime == start
            endTime == end
            tags.size() == 1
            tags[0].name() == "Outdoor"
        }

        when: "configuring virtual shift"
        Shift virtualShift = new ShiftBuilder(null, null, faker)
                .forProject(projId)
                .asVirtual()
                .build()

        then: "virtual shift has no location"
        verifyAll(virtualShift) {
            project.id() == projId
            isVirtual
            location == null
        }

        when: "generating create shift command for physical shift"
        CreateShiftCommand createCmd = new ShiftBuilder(null, null, faker)
                .forProject(projId)
                .atLocation(loc)
                .withTimes(start, end)
                .withTag(tag)
                .asCommand()

        then: "create command has valid attributes and location logic"
        verifyAll(createCmd) {
            projectId() == projId
            !isVirtual()
            locationId() == loc.id()
            startTime() == start
            endTime() == end
            tagIds() == [tag.id()]
            isValidLocationLogic()
        }

        when: "generating update shift command for virtual shift"
        UpdateShiftCommand updateCmd = new ShiftBuilder(null, null, faker)
                .asVirtual()
                .withTimes(start, end)
                .asUpdateCommand()

        then: "update command is valid"
        verifyAll(updateCmd) {
            isVirtual()
            locationId() == null
            startTime() == start
            endTime() == end
            isValidLocationLogic()
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
            dataBuilder.project() != null
            dataBuilder.shift() != null
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
