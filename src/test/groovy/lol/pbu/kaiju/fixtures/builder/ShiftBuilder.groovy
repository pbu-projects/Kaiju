package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.domain.Tag
import lol.pbu.kaiju.dto.CreateShiftCommand
import lol.pbu.kaiju.dto.UpdateShiftCommand
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.ShiftRepository
import net.datafaker.Faker

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

class ShiftBuilder {

    private final ShiftRepository shiftRepository
    private final KaijuDataBuilder dataBuilder
    private final Faker faker

    private UUID id
    private Project project
    private UUID projectId
    private boolean isVirtual = false
    private Location location
    private UUID locationId
    private OffsetDateTime startTime
    private OffsetDateTime endTime
    private Integer capacity = 10
    private final List<Tag> tags = []

    ShiftBuilder(
            ShiftRepository shiftRepository,
            KaijuDataBuilder dataBuilder,
            Faker faker
    ) {
        this.shiftRepository = shiftRepository
        this.dataBuilder = dataBuilder
        this.faker = faker
    }

    ShiftBuilder withId(UUID id) {
        this.id = id
        return this
    }

    ShiftBuilder forProject(Project project) {
        this.project = project
        this.projectId = project?.id()
        return this
    }

    ShiftBuilder forProject(UUID projectId) {
        this.projectId = projectId
        this.project = null
        return this
    }

    ShiftBuilder asVirtual() {
        this.isVirtual = true
        this.location = null
        this.locationId = null
        return this
    }

    ShiftBuilder withVirtual(boolean isVirtual) {
        this.isVirtual = isVirtual
        if (isVirtual) {
            this.location = null
            this.locationId = null
        }
        return this
    }

    ShiftBuilder atLocation(Location location) {
        this.location = location
        this.locationId = location?.id()
        this.isVirtual = false
        return this
    }

    ShiftBuilder atLocation(UUID locationId) {
        this.locationId = locationId
        this.location = null
        this.isVirtual = false
        return this
    }

    ShiftBuilder withCapacity(Integer capacity) {
        this.capacity = capacity
        return this
    }

    ShiftBuilder withTimes(OffsetDateTime startTime, OffsetDateTime endTime) {
        this.startTime = startTime
        this.endTime = endTime
        return this
    }

    ShiftBuilder withStartTime(OffsetDateTime startTime) {
        this.startTime = startTime
        return this
    }

    ShiftBuilder withEndTime(OffsetDateTime endTime) {
        this.endTime = endTime
        return this
    }

    ShiftBuilder withTag(Tag tag) {
        if (tag != null) {
            this.tags.add(tag)
        }
        return this
    }

    ShiftBuilder withTags(List<Tag> tags) {
        if (tags != null) {
            this.tags.addAll(tags)
        }
        return this
    }

    Integer getCapacity() {
        return capacity
    }

    Shift build() {
        Project proj = resolveBuildProject()
        Location loc = resolveBuildLocation()
        OffsetDateTime start = startTime ?: defaultStartTime()
        OffsetDateTime end = endTime ?: start.plusHours(2)

        return new Shift(
                id,
                proj,
                isVirtual,
                loc,
                start,
                end,
                new ArrayList<>(tags)
        )
    }

    Shift persist() {
        assert shiftRepository != null : "Cannot persist shift without a ShiftRepository"
        Project proj = resolvePersistedProject()
        Location loc = resolvePersistedLocation()
        OffsetDateTime start = startTime ?: defaultStartTime()
        OffsetDateTime end = endTime ?: start.plusHours(2)

        Shift toSave = new Shift(
                id,
                proj,
                isVirtual,
                loc,
                start,
                end,
                []
        )
        Shift saved = shiftRepository.save(toSave)

        if (capacity != null && dataBuilder != null) {
            dataBuilder.updateShiftCapacity(saved.id(), capacity)
        }

        if (!tags.isEmpty() && dataBuilder != null) {
            tags.forEach { tag ->
                dataBuilder.addShiftTag(saved.id(), tag.id())
            }
        }

        return new Shift(
                saved.id(),
                saved.project(),
                saved.isVirtual(),
                saved.location(),
                saved.startTime(),
                saved.endTime(),
                new ArrayList<>(tags)
        )
    }

    CreateShiftCommand asCommand() {
        UUID projId = projectId ?: project?.id()
        if (projId == null && dataBuilder != null) {
            projId = dataBuilder.project().persist().id()
        } else if (projId == null) {
            projId = UUID.randomUUID()
        }

        UUID locId = null
        if (!isVirtual) {
            locId = locationId ?: location?.id()
            if (locId == null && dataBuilder != null) {
                locId = dataBuilder.location().persist().id()
            } else if (locId == null) {
                locId = UUID.randomUUID()
            }
        }

        OffsetDateTime start = startTime ?: defaultStartTime()
        OffsetDateTime end = endTime ?: start.plusHours(2)
        List<UUID> tagIds = tags.isEmpty() ? null : tags.stream().map { it.id() }.toList()

        return new CreateShiftCommand(
                projId,
                isVirtual,
                locId,
                start,
                end,
                tagIds
        )
    }

    UpdateShiftCommand asUpdateCommand() {
        UUID locId = null
        if (!isVirtual) {
            locId = locationId ?: location?.id()
            if (locId == null && dataBuilder != null) {
                locId = dataBuilder.location().persist().id()
            } else if (locId == null) {
                locId = UUID.randomUUID()
            }
        }

        OffsetDateTime start = startTime ?: defaultStartTime()
        OffsetDateTime end = endTime ?: start.plusHours(2)
        List<UUID> tagIds = tags.isEmpty() ? null : tags.stream().map { it.id() }.toList()

        return new UpdateShiftCommand(
                isVirtual,
                locId,
                start,
                end,
                tagIds
        )
    }

    private Project resolveBuildProject() {
        if (project != null) {
            return project
        }
        if (projectId != null) {
            Organization dummyOrg = new Organization(UUID.randomUUID(), "Org", "https://example.org", null, true, VerificationStatus.VERIFIED, null, [])
            return new Project(projectId, dummyOrg, null, "Project", "Description", ProjectType.STANDARD, ProjectStatus.ACTIVE, OffsetDateTime.now(ZoneOffset.UTC), null, null, [], [])
        }
        if (dataBuilder != null) {
            return dataBuilder.project().build()
        }
        Organization dummyOrg = new Organization(UUID.randomUUID(), "Org", "https://example.org", null, true, VerificationStatus.VERIFIED, null, [])
        return new Project(UUID.randomUUID(), dummyOrg, null, "Project", "Description", ProjectType.STANDARD, ProjectStatus.ACTIVE, OffsetDateTime.now(ZoneOffset.UTC), null, null, [], [])
    }

    private Project resolvePersistedProject() {
        if (project != null) {
            return project
        }
        if (projectId != null) {
            Organization dummyOrg = new Organization(UUID.randomUUID(), null, null, null, true, null, null, [])
            return new Project(projectId, dummyOrg, null, null, null, null, null, null, null, null, [], [])
        }
        assert dataBuilder != null : "Cannot persist shift without project or KaijuDataBuilder instance"
        return dataBuilder.project().persist()
    }

    private Location resolveBuildLocation() {
        if (isVirtual) {
            return null
        }
        if (location != null) {
            return location
        }
        if (locationId != null) {
            return new Location(locationId, "Location", "123 St", "Denver", "CO", "80202", "US", null, "America/Denver")
        }
        return new Location(UUID.randomUUID(), "Location", "123 St", "Denver", "CO", "80202", "US", null, "America/Denver")
    }

    private Location resolvePersistedLocation() {
        if (isVirtual) {
            return null
        }
        if (location != null) {
            return location
        }
        if (locationId != null) {
            return new Location(locationId, null, null, null, null, null, null, null, null)
        }
        assert dataBuilder != null : "Cannot persist physical shift without location or KaijuDataBuilder instance"
        return dataBuilder.location().persist()
    }

    private OffsetDateTime defaultStartTime() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).truncatedTo(ChronoUnit.SECONDS)
    }
}
