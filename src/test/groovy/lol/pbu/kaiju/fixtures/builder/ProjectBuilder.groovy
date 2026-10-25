package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Boundary
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.dto.CoordinateDto
import lol.pbu.kaiju.dto.CreateProjectCommand
import lol.pbu.kaiju.dto.ProjectBoundaryCommand
import lol.pbu.kaiju.dto.ProjectLocationCommand
import lol.pbu.kaiju.dto.UpdateProjectCommand
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.ProjectRepository
import net.datafaker.Faker

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ProjectBuilder {

    private final ProjectRepository projectRepository
    private final KaijuDataBuilder dataBuilder
    private final Faker faker

    private UUID id
    private Organization organization
    private UUID organizationId
    private AdministrativeRegion managingRegion
    private UUID managingRegionId
    private String title
    private String description
    private ProjectType projectType = ProjectType.STANDARD
    private ProjectStatus status = ProjectStatus.ACTIVE
    private OffsetDateTime createdAt
    private OffsetDateTime deletedAt
    private User deletedBy
    private final List<Location> locations = []
    private final List<Boundary> boundaries = []
    private List<ProjectBoundaryCommand> customCommandBoundaries
    private List<ProjectLocationCommand> customCommandLocations
    private boolean explicitNoOrg = false
    private boolean explicitNullLocations = false

    ProjectBuilder(
            ProjectRepository projectRepository,
            KaijuDataBuilder dataBuilder,
            Faker faker
    ) {
        this.projectRepository = projectRepository
        this.dataBuilder = dataBuilder
        this.faker = faker
    }

    ProjectBuilder withId(UUID id) {
        this.id = id
        return this
    }

    ProjectBuilder forOrg(Organization org) {
        this.organization = org
        this.organizationId = org?.id()
        this.explicitNoOrg = false
        return this
    }

    ProjectBuilder forOrg(UUID orgId) {
        this.organizationId = orgId
        this.organization = null
        this.explicitNoOrg = false
        return this
    }

    ProjectBuilder withoutOrg() {
        this.explicitNoOrg = true
        this.organization = null
        this.organizationId = null
        return this
    }

    ProjectBuilder inRegion(AdministrativeRegion region) {
        this.managingRegion = region
        this.managingRegionId = region?.id()
        return this
    }

    ProjectBuilder inRegion(UUID regionId) {
        this.managingRegionId = regionId
        this.managingRegion = null
        return this
    }

    ProjectBuilder withTitle(String title) {
        this.title = title
        return this
    }

    ProjectBuilder withDescription(String description) {
        this.description = description
        return this
    }

    ProjectBuilder withType(ProjectType projectType) {
        this.projectType = projectType
        return this
    }

    ProjectBuilder withStatus(ProjectStatus status) {
        this.status = status
        return this
    }

    ProjectBuilder withCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt
        return this
    }

    ProjectBuilder deleted(User deletedBy = null, OffsetDateTime deletedAt = OffsetDateTime.now(ZoneOffset.UTC)) {
        this.deletedBy = deletedBy
        this.deletedAt = deletedAt
        return this
    }

    ProjectBuilder atLocation(Location location) {
        if (location != null) {
            this.locations.add(location)
        }
        return this
    }

    ProjectBuilder atLocation(UUID locationId) {
        if (locationId != null) {
            this.locations.add(new Location(locationId, null, null, null, null, null, null, null, null))
        }
        return this
    }

    ProjectBuilder atLocations(List<Location> locs) {
        if (locs != null) {
            this.locations.addAll(locs)
        }
        return this
    }

    ProjectBuilder withNullLocations() {
        this.explicitNullLocations = true
        return this
    }

    ProjectBuilder withBoundary(Boundary boundary) {
        if (boundary != null) {
            this.boundaries.add(boundary)
        }
        return this
    }

    ProjectBuilder withBoundaries(List<Boundary> bounds) {
        if (bounds != null) {
            this.boundaries.addAll(bounds)
        }
        return this
    }

    ProjectBuilder withCommandBoundaries(List<ProjectBoundaryCommand> boundaries) {
        this.customCommandBoundaries = boundaries
        return this
    }

    ProjectBuilder withCommandLocations(List<ProjectLocationCommand> locations) {
        this.customCommandLocations = locations
        return this
    }

    Project build() {
        Organization org = resolveBuildOrganization()
        AdministrativeRegion region = resolveBuildRegion()
        String projectTitle = title ?: defaultTitle()
        String projectDesc = description ?: defaultDescription()
        OffsetDateTime created = createdAt ?: OffsetDateTime.now(ZoneOffset.UTC)

        return new Project(
                id,
                org,
                region,
                projectTitle,
                projectDesc,
                projectType,
                status,
                created,
                deletedAt,
                deletedBy,
                explicitNullLocations ? null : new ArrayList<>(locations),
                new ArrayList<>(boundaries)
        )
    }

    Project persist() {
        assert projectRepository != null : "Cannot persist project without a ProjectRepository"
        Organization org = resolvePersistedOrganization()
        AdministrativeRegion region = resolvePersistedRegion()
        String projectTitle = title ?: defaultTitle()
        String projectDesc = description ?: defaultDescription()
        OffsetDateTime created = createdAt ?: OffsetDateTime.now(ZoneOffset.UTC)

        Project toSave = new Project(
                id,
                org,
                region,
                projectTitle,
                projectDesc,
                projectType,
                status,
                created,
                deletedAt,
                deletedBy,
                [],
                []
        )
        Project saved = projectRepository.save(toSave)

        if (!locations.isEmpty()) {
            assert dataBuilder != null : "Cannot persist project locations without a KaijuDataBuilder instance"
            locations.forEach { loc ->
                dataBuilder.addProjectLocation(saved.id(), loc.id())
            }
        }

        if (!boundaries.isEmpty()) {
            assert dataBuilder != null : "Cannot persist project boundaries without a KaijuDataBuilder instance"
            boundaries.forEach { b ->
                dataBuilder.addProjectBoundary(saved.id(), b.id())
            }
        }

        return new Project(
                saved.id(),
                saved.organization(),
                saved.managingRegion(),
                saved.title(),
                saved.description(),
                saved.projectType(),
                saved.status(),
                saved.createdAt(),
                saved.deletedAt(),
                saved.deletedBy(),
                new ArrayList<>(locations),
                new ArrayList<>(boundaries)
        )
    }

    CreateProjectCommand asCommand() {
        UUID orgId = organizationId ?: organization?.id()
        if (orgId == null && dataBuilder != null) {
            orgId = dataBuilder.organization().persist().id()
        } else if (orgId == null) {
            orgId = UUID.randomUUID()
        }

        UUID regId = managingRegionId ?: managingRegion?.id()
        String projectTitle = title ?: defaultTitle()
        String projectDesc = description ?: defaultDescription()
        List<ProjectBoundaryCommand> boundaryCommands = customCommandBoundaries ?: defaultCommandBoundaries()
        List<ProjectLocationCommand> locationCommands = customCommandLocations ?: (locations.isEmpty() ? [] : defaultCommandLocations())

        return new CreateProjectCommand(
                orgId,
                regId,
                projectTitle,
                projectDesc,
                projectType,
                status,
                locationCommands,
                boundaryCommands
        )
    }

    UpdateProjectCommand asUpdateCommand() {
        UUID orgId = organizationId ?: organization?.id()
        UUID regId = managingRegionId ?: managingRegion?.id()
        String projectTitle = title ?: defaultTitle()
        String projectDesc = description ?: defaultDescription()
        List<ProjectBoundaryCommand> boundaryCommands = customCommandBoundaries ?: defaultCommandBoundaries()
        List<ProjectLocationCommand> locationCommands = customCommandLocations ?: (locations.isEmpty() ? [] : defaultCommandLocations())

        return new UpdateProjectCommand(
                orgId,
                regId,
                projectTitle,
                projectDesc,
                projectType,
                locationCommands,
                boundaryCommands
        )
    }

    private Organization resolveBuildOrganization() {
        if (explicitNoOrg) {
            return null
        }
        if (organization != null) {
            return organization
        }
        if (organizationId != null) {
            return new Organization(organizationId, "Org ${UUID.randomUUID()}", "https://example.org", null, true, VerificationStatus.VERIFIED, null, [])
        }
        UUID newOrgId = UUID.randomUUID()
        return new Organization(newOrgId, defaultOrgName(), "https://example.org", null, true, VerificationStatus.VERIFIED, null, [])
    }

    private Organization resolvePersistedOrganization() {
        if (organization != null) {
            return organization
        }
        if (organizationId != null) {
            return new Organization(organizationId, null, null, null, true, null, null, [])
        }
        assert dataBuilder != null : "Cannot auto-associate with an organization without a KaijuDataBuilder instance"
        return dataBuilder.organization().persist()
    }

    private AdministrativeRegion resolveBuildRegion() {
        if (managingRegion != null) {
            return managingRegion
        }
        if (managingRegionId != null) {
            return new AdministrativeRegion(managingRegionId, "Region ${UUID.randomUUID()}", null, null)
        }
        return null
    }

    private AdministrativeRegion resolvePersistedRegion() {
        if (managingRegion != null) {
            return managingRegion
        }
        if (managingRegionId != null) {
            return new AdministrativeRegion(managingRegionId, null, null, null)
        }
        return null
    }

    private String defaultTitle() {
        return faker != null ? "Project ${faker.book().title()} ${UUID.randomUUID().toString().substring(0, 8)}" : "Project ${UUID.randomUUID()}"
    }

    private String defaultDescription() {
        return faker != null
                ? "Description for project testing: ${faker.lorem().paragraph()}"
                : "Default project description satisfying the minimum character limit easily."
    }

    private String defaultOrgName() {
        return faker != null ? "Org ${faker.company().name()} ${UUID.randomUUID().toString().substring(0, 8)}" : "Org ${UUID.randomUUID()}"
    }

    private List<ProjectBoundaryCommand> defaultCommandBoundaries() {
        return [
                new ProjectBoundaryCommand(
                        "Default Boundary",
                        [
                                new CoordinateDto(-105.0, 39.0),
                                new CoordinateDto(-104.0, 39.0),
                                new CoordinateDto(-104.0, 40.0),
                                new CoordinateDto(-105.0, 39.0)
                        ]
                )
        ]
    }

    private List<ProjectLocationCommand> defaultCommandLocations() {
        return locations.stream()
                .map { loc ->
                    new ProjectLocationCommand(
                            loc.name() ?: "Location ${UUID.randomUUID().toString().substring(0, 8)}",
                            loc.addressLine() ?: "123 Main St",
                            loc.city() ?: "Denver",
                            loc.stateProvince() ?: "CO",
                            loc.postalCode() ?: "80202",
                            loc.countryCode() ?: "US",
                            loc.geom() != null ? loc.geom().coordinate.x : -104.9903,
                            loc.geom() != null ? loc.geom().coordinate.y : 39.7392
                    )
                }
                .toList()
    }
}
