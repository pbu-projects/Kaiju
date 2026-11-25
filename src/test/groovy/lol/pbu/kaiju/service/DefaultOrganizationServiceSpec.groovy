package lol.pbu.kaiju.service

import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.BoundaryRepository
import lol.pbu.kaiju.repository.LocationRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.PermissionQueryRepository
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.security.AuthorizationService
import org.locationtech.jts.geom.GeometryFactory
import spock.lang.Unroll

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@MicronautTest(transactional = false)
class DefaultOrganizationServiceSpec extends BaseControllerSpec {

    @Inject
    OrganizationService organizationService

    @Inject
    OrganizationRepository organizationRepository

    @Unroll
    def "createOrganization fails with #expectedStatus when #scenario"() {
        given: "an admin user"
        def admin = dataBuilder.user().asAdmin().persist()
        UUID actorId = useMissingCreator ? UUID.randomUUID() : admin.id()
        UUID parentId = useMissingParent ? UUID.randomUUID() : null

        when: "creating the organization"
        organizationService.createOrganization(
                Organization.forCreation(orgName, null, parentId, true),
                actorId
        )

        then: "the expected error is thrown"
        HttpStatusException ex = thrown()
        verifyAll(ex) {
            status == expectedStatus
            expectedMessage == null || message == expectedMessage
        }

        where:
        scenario                    | orgName               | useMissingCreator | useMissingParent || expectedStatus          | expectedMessage
        "creator user is missing"   | "Missing Creator Org" | true              | false            || HttpStatus.UNAUTHORIZED | null
        "parent org does not exist" | "Orphan Child Org"    | false             | true             || HttpStatus.BAD_REQUEST  | "Parent organization does not exist"
    }

    def "createOrganization saves a child org with null locations and unverified status"() {
        given: "an admin user and a parent organization"
        def admin = dataBuilder.user().asAdmin().persist()
        def parentOrg = dataBuilder.organization().withName("Parent Org").asPublic().persist()

        when: "creating an organization with null locations and a valid parent ID"
        Organization created = track(organizationService.createOrganization(
                new Organization(null, "Child With Null Locations", null, parentOrg.id(), true, VerificationStatus.UNVERIFIED, null, null),
                admin.id()
        ))

        then: "organization is saved with parent ID and unverified status"
        verifyAll(created) {
            id() != null
            parentId() == parentOrg.id()
            verificationStatus() == VerificationStatus.UNVERIFIED
        }
    }

    def "lists saved organizations in pages"() {
        given: "a saved organization"
        dataBuilder.organization().withName("Queryable Org").asPublic().persist()

        when: "listing organizations"
        def page = organizationService.getOrganizations(CursoredPageable.from(10, Sort.UNSORTED))

        then: "the page contains organizations"
        !page.content.isEmpty()
    }

    def "finds a saved organization by its ID"() {
        given: "a saved organization"
        def saved = dataBuilder.organization().withName("Queryable Org").asPublic().persist()

        when: "looking up the organization by ID"
        def found = organizationService.getOrganizationById(saved.id())

        then: "the saved organization is returned"
        verifyAll {
            found.isPresent()
            found.get().id() == saved.id()
        }
    }

    @Unroll
    def "updateOrganization fails with #expectedStatus when #scenario"() {
        given: "an admin user and an existing organization"
        def admin = dataBuilder.user().asAdmin().persist()
        def existing = dataBuilder.organization().withName("Existing Org").asPublic().persist()
        UUID targetId = missingOrg ? UUID.randomUUID() : existing.id()
        UUID parentId = selfParent ? existing.id() : (missingParent ? UUID.randomUUID() : null)

        when: "updating the organization"
        organizationService.updateOrganization(
                targetId,
                Organization.forUpdate(targetId, "Updated Name", null, parentId, true),
                admin.id()
        )

        then: "the expected error is thrown"
        HttpStatusException ex = thrown()
        ex.status == expectedStatus

        where:
        scenario                     | missingOrg | selfParent | missingParent || expectedStatus
        "organization is missing"    | true       | false      | false         || HttpStatus.NOT_FOUND
        "parent is the org itself"   | false      | true       | false         || HttpStatus.BAD_REQUEST
        "parent org does not exist"  | false      | false      | true          || HttpStatus.BAD_REQUEST
    }

    def "updateOrganization reparents an organization to another existing parent"() {
        given: "an admin user, a child org, and two parent orgs"
        def admin = dataBuilder.user().asAdmin().persist()
        def firstParent = dataBuilder.organization().withName("First Parent Org").asPublic().persist()
        def secondParent = dataBuilder.organization().withName("Second Parent Org").asPublic().persist()
        def child = dataBuilder.organization().withName("Child Org").withParent(firstParent).asPublic().persist()

        when: "updating the organization with a new parent ID"
        Organization updated = organizationService.updateOrganization(
                child.id(),
                Organization.forUpdate(child.id(), "Reparented Org", "https://example.org", secondParent.id(), true),
                admin.id()
        )

        then: "name and parent ID are updated"
        verifyAll(updated) {
            name() == "Reparented Org"
            parentId() == secondParent.id()
        }
    }

    def "createOrganization keeps null parentId for root organizations with non-null locations"() {
        given: "an admin user"
        def admin = dataBuilder.user().asAdmin().persist()

        when: "creating a root organization with null parentId and non-null locations"
        Organization rootOrg = track(organizationService.createOrganization(
                Organization.forCreation("Root Org", null, null, true),
                admin.id()
        ))

        then: "root organization is created with null parentId"
        verifyAll(rootOrg) {
            id() != null
            parentId() == null
        }
    }

    def "updateOrganization keeps null parentId when updating a root organization"() {
        given: "an admin user and a root organization"
        def admin = dataBuilder.user().asAdmin().persist()
        Organization rootOrg = dataBuilder.organization().withName("Root Org").asPublic().persist()

        when: "updating the root organization with null parentId"
        Organization updatedRoot = organizationService.updateOrganization(
                rootOrg.id(),
                Organization.forUpdate(rootOrg.id(), "Root Org Updated", null, null, true),
                admin.id()
        )

        then: "name updates and parentId stays null"
        verifyAll(updatedRoot) {
            name() == "Root Org Updated"
            parentId() == null
        }
    }

    @Unroll
    def "updateVerificationStatus fails with #expectedStatus when #scenario"() {
        given: "an admin user and an existing verified organization"
        def admin = dataBuilder.user().asAdmin().persist()
        def verifiedOrg = dataBuilder.organization().withName("Verified Org").asPublic().verified().persist()
        UUID targetId = missingOrg ? UUID.randomUUID() : verifiedOrg.id()

        when: "updating verification status"
        organizationService.updateVerificationStatus(targetId, targetStatus, reason, admin.id())

        then: "the expected error is thrown"
        HttpStatusException ex = thrown()
        ex.status == expectedStatus

        where:
        scenario                     | missingOrg | targetStatus                  | reason              || expectedStatus
        "organization is missing"    | true       | VerificationStatus.VERIFIED   | "Missing"           || HttpStatus.NOT_FOUND
        "transition is not allowed"  | false      | VerificationStatus.UNVERIFIED | "Invalid downgrade" || HttpStatus.BAD_REQUEST
    }

    def "updateVerificationStatus transitions an unverified organization to verified"() {
        given: "an admin user and an unverified organization"
        def admin = dataBuilder.user().asAdmin().persist()
        def org = dataBuilder.organization().withName("Unverified Org").asPublic().unverified().persist()

        when: "updating verification status to VERIFIED"
        Organization verified = organizationService.updateVerificationStatus(org.id(), VerificationStatus.VERIFIED, "Verified ok", admin.id())

        then: "status is updated to VERIFIED"
        verified.verificationStatus() == VerificationStatus.VERIFIED
    }

    def "deleteOrganization throws NOT_FOUND when the organization is missing"() {
        given: "an admin user"
        def admin = dataBuilder.user().asAdmin().persist()

        when: "deleting a non-existent organization"
        organizationService.deleteOrganization(UUID.randomUUID(), admin.id())

        then: "NOT_FOUND is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.NOT_FOUND
    }

    def "deleteOrganization removes an existing organization"() {
        given: "an admin user and an existing organization"
        def admin = dataBuilder.user().asAdmin().persist()
        def org = dataBuilder.organization().withName("Disposable Org").asPublic().persist()

        when: "deleting the organization"
        organizationService.deleteOrganization(org.id(), admin.id())

        then: "the organization no longer exists"
        !organizationRepository.existsById(org.id())
    }

    def "updateVerificationStatus defaults null current verification status to UNVERIFIED"() {
        given: "a unit-tested DefaultOrganizationService whose repository returns an organization with null verificationStatus"
        OrganizationRepository mockOrgRepo = Mock(OrganizationRepository)
        UserRepository mockUserRepo = Mock(UserRepository)
        PermissionQueryRepository mockPermRepo = Mock(PermissionQueryRepository)
        AuthorizationService unitAuth = new AuthorizationService(
                mockPermRepo,
                mockUserRepo,
                Mock(LocationRepository),
                Mock(BoundaryRepository),
                new GeometryFactory()
        )
        AuditService mockAudit = Mock(AuditService)
        DefaultOrganizationService unitService = new DefaultOrganizationService(mockOrgRepo, mockUserRepo, unitAuth, mockAudit)

        UUID actorId = UUID.randomUUID()
        UUID orgId = UUID.randomUUID()
        User admin = new User(actorId, "admin@example.com", UserRole.GLOBAL_ADMIN, OffsetDateTime.now(ZoneOffset.UTC))
        Organization nullStatusOrg = new Organization(orgId, "Null Status Org", null, null, true, null, null, [])

        mockUserRepo.findById(actorId) >> Optional.of(admin)
        mockOrgRepo.findById(orgId) >> Optional.of(nullStatusOrg)
        mockOrgRepo.update(_ as Organization) >> { Organization o -> o }

        when: "updating verification status to VERIFIED"
        Organization result = unitService.updateVerificationStatus(orgId, VerificationStatus.VERIFIED, "Defaulted from null", actorId)

        then: "status transitions from UNVERIFIED to VERIFIED and audit is recorded"
        verifyAll(result) {
            id() == orgId
            verificationStatus() == VerificationStatus.VERIFIED
        }
        1 * mockAudit.recordOrganizationAudit(
                { Organization o -> o.id() == orgId && o.verificationStatus() == VerificationStatus.VERIFIED },
                admin,
                "UNVERIFIED",
                "VERIFIED",
                "Defaulted from null"
        )
    }
}
