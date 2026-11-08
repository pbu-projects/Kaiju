package lol.pbu.kaiju.service

import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.dto.CoordinateDto
import lol.pbu.kaiju.dto.CreateAdministrativeRegionCommand
import lol.pbu.kaiju.dto.UpdateAdministrativeRegionCommand
import lol.pbu.kaiju.model.RegionAuditAction
import lol.pbu.kaiju.repository.AdministrativeRegionAuditLogRepository
import lol.pbu.kaiju.repository.AdministrativeRegionRepository

import java.util.UUID

@MicronautTest(transactional = false)
class AdministrativeRegionServiceSpec extends BaseControllerSpec {

    @Inject
    AdministrativeRegionService regionService

    @Inject
    AdministrativeRegionRepository regionRepository

    @Inject
    AdministrativeRegionAuditLogRepository auditLogRepository

    private User testAdminUser

    def setup() {
        testAdminUser = dataBuilder.user().asAdmin().persist()
    }

    private List<CoordinateDto> validCoordinates() {
        [
                new CoordinateDto(-105.0, 39.0),
                new CoordinateDto(-104.0, 39.0),
                new CoordinateDto(-104.0, 40.0),
                new CoordinateDto(-105.0, 40.0),
                new CoordinateDto(-105.0, 39.0)
        ]
    }

    private List<CoordinateDto> bowtieCoordinates() {
        // Self-intersecting figure-8 bowtie polygon (topologically invalid)
        [
                new CoordinateDto(0.0, 0.0),
                new CoordinateDto(2.0, 2.0),
                new CoordinateDto(2.0, 0.0),
                new CoordinateDto(0.0, 2.0),
                new CoordinateDto(0.0, 0.0)
        ]
    }

    def "CREATE | should create an administrative region and record an immutable audit log"() {
        given: "a valid create command"
        def command = new CreateAdministrativeRegionCommand("Metro North", null, validCoordinates())

        when: "the region is created via the service"
        AdministrativeRegion region = regionService.createAdministrativeRegion(command, testAdminUser.id())
        track(region)

        then: "the region is saved"
        verifyAll {
            region.id() != null
            region.name() == "Metro North"
        }

        and: "an audit log is recorded with CREATE action"
        def logs = auditLogRepository.findByRegionId(region.id(), CursoredPageable.from(10, Sort.UNSORTED))
        verifyAll {
            logs.content.size() == 1
            logs.content[0].action() == RegionAuditAction.CREATE
            logs.content[0].actor().id() == testAdminUser.id()
            logs.content[0].metadata().contains("Metro North")
        }
    }

    def "CREATE | should fail when parent administrative region does not exist"() {
        given: "a command referencing a non-existent parent ID"
        UUID missingParentId = UUID.randomUUID()
        def command = new CreateAdministrativeRegionCommand("Child Region", missingParentId, validCoordinates())

        when: "attempting to create the region"
        regionService.createAdministrativeRegion(command, testAdminUser.id())

        then: "HTTP 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "CREATE | should fail when geometry is topologically invalid"() {
        given: "a command with a self-intersecting polygon"
        def command = new CreateAdministrativeRegionCommand("Invalid Region", null, bowtieCoordinates())

        when: "attempting to create the region"
        regionService.createAdministrativeRegion(command, testAdminUser.id())

        then: "HTTP 422 Unprocessable Entity is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.UNPROCESSABLE_ENTITY
    }

    def "CREATE | should fail when actor user does not exist"() {
        given: "a non-existent actor UUID"
        UUID missingUserId = UUID.randomUUID()
        def command = new CreateAdministrativeRegionCommand("Valid Region", null, validCoordinates())

        when: "attempting to create the region"
        regionService.createAdministrativeRegion(command, missingUserId)

        then: "HTTP 401 Unauthorized is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "UPDATE | should update an existing administrative region and record an audit log"() {
        given: "an existing administrative region"
        def createCmd = new CreateAdministrativeRegionCommand("Original Name", null, validCoordinates())
        AdministrativeRegion initial = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())
        track(initial)

        and: "an update command"
        def updateCmd = new UpdateAdministrativeRegionCommand("Renamed Region", null, validCoordinates())

        when: "updating the administrative region"
        AdministrativeRegion updated = regionService.updateAdministrativeRegion(initial.id(), updateCmd, testAdminUser.id())

        then: "the region name is updated"
        verifyAll {
            updated.id() == initial.id()
            updated.name() == "Renamed Region"
        }

        and: "both CREATE and UPDATE audit logs are present"
        def logs = auditLogRepository.findByRegionId(initial.id(), CursoredPageable.from(10, Sort.UNSORTED))
        verifyAll {
            logs.content.size() == 2
            logs.content.any { it.action() == RegionAuditAction.UPDATE && it.metadata().contains("Original Name") && it.metadata().contains("Renamed Region") }
        }
    }

    def "UPDATE | should reject an administrative region setting itself as parent"() {
        given: "an existing administrative region"
        def createCmd = new CreateAdministrativeRegionCommand("Self Parent Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())
        track(region)

        and: "an update command setting parentRegionId to its own ID"
        def updateCmd = new UpdateAdministrativeRegionCommand("Self Parent Region", region.id(), validCoordinates())

        when: "attempting to update the region"
        regionService.updateAdministrativeRegion(region.id(), updateCmd, testAdminUser.id())

        then: "HTTP 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "UPDATE | should reject setting a non-existent parent administrative region"() {
        given: "an existing administrative region"
        def createCmd = new CreateAdministrativeRegionCommand("Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())
        track(region)

        and: "an update command setting a non-existent parent ID"
        def updateCmd = new UpdateAdministrativeRegionCommand("Region", UUID.randomUUID(), validCoordinates())

        when: "attempting to update the region"
        regionService.updateAdministrativeRegion(region.id(), updateCmd, testAdminUser.id())

        then: "HTTP 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "UPDATE | should return 404 when updating non-existent region"() {
        given: "a non-existent region ID"
        UUID missingId = UUID.randomUUID()
        def updateCmd = new UpdateAdministrativeRegionCommand("New Name", null, validCoordinates())

        when: "attempting to update"
        regionService.updateAdministrativeRegion(missingId, updateCmd, testAdminUser.id())

        then: "HTTP 404 Not Found is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "DELETE | should delete an administrative region and preserve immutable audit log"() {
        given: "an existing administrative region"
        def createCmd = new CreateAdministrativeRegionCommand("Region To Delete", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())

        when: "deleting the administrative region"
        regionService.deleteAdministrativeRegion(region.id(), testAdminUser.id())

        then: "the region no longer exists in the repository"
        !regionRepository.existsById(region.id())

        and: "both CREATE and DELETE audit logs persist intact"
        def logs = auditLogRepository.findByRegionId(region.id(), CursoredPageable.from(10, Sort.UNSORTED))
        verifyAll {
            logs.content.size() == 2
            logs.content.any { it.action() == RegionAuditAction.DELETE && it.metadata().contains("Region To Delete") }
        }
    }

    def "DELETE | should return 404 when deleting a non-existent administrative region"() {
        given: "a non-existent region ID"
        UUID missingId = UUID.randomUUID()

        when: "attempting to delete"
        regionService.deleteAdministrativeRegion(missingId, testAdminUser.id())

        then: "HTTP 404 Not Found is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.NOT_FOUND
    }
}
