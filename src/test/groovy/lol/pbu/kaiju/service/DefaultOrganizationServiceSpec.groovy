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

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@MicronautTest(transactional = false)
class DefaultOrganizationServiceSpec extends BaseControllerSpec {

    @Inject
    OrganizationService organizationService

    @Inject
    OrganizationRepository organizationRepository

    def "handles creation, parent checks, updates, deletions, and verification transitions"() {
        given: "an admin user and a parent organization"
        def admin = dataBuilder.user().asAdmin().persist()
        def parentOrg = dataBuilder.organization().withName("Parent Org").asPublic().persist()

        when: "creating an organization with a missing creator user"
        organizationService.createOrganization(
                Organization.forCreation("Missing Creator Org", null, null, true),
                UUID.randomUUID()
        )

        then:
        HttpStatusException missingCreatorEx = thrown()
        missingCreatorEx.status == HttpStatus.UNAUTHORIZED

        when: "creating an organization with a non-existent parent ID"
        organizationService.createOrganization(
                Organization.forCreation("Orphan Child Org", null, UUID.randomUUID(), true),
                admin.id()
        )

        then:
        HttpStatusException missingParentEx = thrown()
        verifyAll(missingParentEx) {
            status == HttpStatus.BAD_REQUEST
            message == "Parent organization does not exist"
        }

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

        and: "getOrganizations and getOrganizationById return the saved organization"
        def page = organizationService.getOrganizations(CursoredPageable.from(10, Sort.UNSORTED))
        def found = organizationService.getOrganizationById(created.id())
        verifyAll {
            !page.content.isEmpty()
            found.isPresent()
            found.get().id() == created.id()
        }

        when: "updating a non-existent organization as admin"
        organizationService.updateOrganization(
                UUID.randomUUID(),
                Organization.forUpdate(UUID.randomUUID(), "Missing", null, null, true),
                admin.id()
        )

        then:
        HttpStatusException missingOrgEx = thrown()
        missingOrgEx.status == HttpStatus.NOT_FOUND

        when: "updating an organization to be its own parent"
        organizationService.updateOrganization(
                created.id(),
                Organization.forUpdate(created.id(), "Self Parent", null, created.id(), true),
                admin.id()
        )

        then:
        HttpStatusException selfParentEx = thrown()
        selfParentEx.status == HttpStatus.BAD_REQUEST

        when: "updating an organization with a non-existent parent ID"
        organizationService.updateOrganization(
                created.id(),
                Organization.forUpdate(created.id(), "Bad Parent", null, UUID.randomUUID(), true),
                admin.id()
        )

        then:
        HttpStatusException badParentEx = thrown()
        badParentEx.status == HttpStatus.BAD_REQUEST

        when: "updating an organization with another existing parent ID"
        def secondParent = dataBuilder.organization().withName("Second Parent Org").asPublic().persist()
        Organization updated = organizationService.updateOrganization(
                created.id(),
                Organization.forUpdate(created.id(), "Reparented Org", "https://example.org", secondParent.id(), true),
                admin.id()
        )

        then:
        verifyAll(updated) {
            name() == "Reparented Org"
            parentId() == secondParent.id()
        }

        when: "updating verification status of a non-existent organization"
        organizationService.updateVerificationStatus(UUID.randomUUID(), VerificationStatus.VERIFIED, "Missing", admin.id())

        then:
        HttpStatusException verifyMissingEx = thrown()
        verifyMissingEx.status == HttpStatus.NOT_FOUND

        when: "updating verification status with a valid transition"
        Organization verified = organizationService.updateVerificationStatus(created.id(), VerificationStatus.VERIFIED, "Verified ok", admin.id())

        then:
        verified.verificationStatus() == VerificationStatus.VERIFIED

        when: "updating verification status with an invalid transition"
        organizationService.updateVerificationStatus(created.id(), VerificationStatus.UNVERIFIED, "Invalid downgrade", admin.id())

        then:
        HttpStatusException invalidTransitionEx = thrown()
        invalidTransitionEx.status == HttpStatus.BAD_REQUEST

        when: "creating an organization with null parentId and non-null locations"
        Organization rootOrg = track(organizationService.createOrganization(
                Organization.forCreation("Root Org", null, null, true),
                admin.id()
        ))

        and: "updating the root organization with null parentId"
        Organization updatedRoot = organizationService.updateOrganization(
                rootOrg.id(),
                Organization.forUpdate(rootOrg.id(), "Root Org Updated", null, null, true),
                admin.id()
        )

        then:
        verifyAll {
            rootOrg.id() != null
            rootOrg.parentId() == null
            updatedRoot.name() == "Root Org Updated"
            updatedRoot.parentId() == null
        }

        when: "deleting a non-existent organization as admin"
        organizationService.deleteOrganization(UUID.randomUUID(), admin.id())

        then:
        HttpStatusException deleteMissingEx = thrown()
        deleteMissingEx.status == HttpStatus.NOT_FOUND

        when: "deleting the created organization as admin"
        organizationService.deleteOrganization(created.id(), admin.id())

        then:
        !organizationRepository.existsById(created.id())
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
