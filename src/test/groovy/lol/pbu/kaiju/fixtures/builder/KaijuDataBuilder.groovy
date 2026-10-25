package lol.pbu.kaiju.fixtures.builder

import jakarta.inject.Inject
import jakarta.inject.Singleton
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Boundary
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.BoundaryRepository
import lol.pbu.kaiju.repository.LocationRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.OrganizationUserRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.repository.RegionUserRepository
import lol.pbu.kaiju.repository.ShiftRepository
import lol.pbu.kaiju.repository.TagRepository
import lol.pbu.kaiju.repository.UserRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

import javax.sql.DataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

@Singleton
class KaijuDataBuilder {

    private final UserRepository userRepository
    private final AdministrativeRegionRepository regionRepository
    private final LocationRepository locationRepository
    private final TagRepository tagRepository
    private final BoundaryRepository boundaryRepository
    private final OrganizationRepository organizationRepository
    private final OrganizationUserRepository organizationUserRepository
    private final RegionUserRepository regionUserRepository
    private final ProjectRepository projectRepository
    private final ShiftRepository shiftRepository
    private final DataSource dataSource
    private final GeometryFactory geometryFactory
    private final Faker faker

    KaijuDataBuilder() {
        this(null, null, null, null, null, null, null, null, null, null, null)
    }

    @Inject
    KaijuDataBuilder(
            UserRepository userRepository,
            AdministrativeRegionRepository regionRepository,
            LocationRepository locationRepository,
            TagRepository tagRepository,
            BoundaryRepository boundaryRepository,
            OrganizationRepository organizationRepository,
            OrganizationUserRepository organizationUserRepository,
            RegionUserRepository regionUserRepository,
            ProjectRepository projectRepository,
            ShiftRepository shiftRepository,
            DataSource dataSource
    ) {
        this.userRepository = userRepository
        this.regionRepository = regionRepository
        this.locationRepository = locationRepository
        this.tagRepository = tagRepository
        this.boundaryRepository = boundaryRepository
        this.organizationRepository = organizationRepository
        this.organizationUserRepository = organizationUserRepository
        this.regionUserRepository = regionUserRepository
        this.projectRepository = projectRepository
        this.shiftRepository = shiftRepository
        this.dataSource = dataSource
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)
        this.faker = new Faker()
    }

    UserBuilder user() {
        return new UserBuilder(userRepository, faker)
    }

    RegionBuilder region() {
        return new RegionBuilder(regionRepository, geometryFactory, faker)
    }

    LocationBuilder location() {
        return new LocationBuilder(locationRepository, geometryFactory, faker)
    }

    TagBuilder tag() {
        return new TagBuilder(tagRepository)
    }

    BoundaryBuilder boundary() {
        return new BoundaryBuilder(boundaryRepository, geometryFactory)
    }

    OrganizationBuilder organization() {
        return new OrganizationBuilder(organizationRepository, this, faker)
    }

    OrganizationUserBuilder organizationUser() {
        return new OrganizationUserBuilder(organizationUserRepository)
    }

    RegionUserBuilder regionUser() {
        return new RegionUserBuilder(regionUserRepository)
    }

    ProjectBuilder project() {
        return new ProjectBuilder(projectRepository, this, faker)
    }

    ShiftBuilder shift() {
        return new ShiftBuilder(shiftRepository, this, faker)
    }

    void linkOrganizationToRegion(UUID orgId, UUID regionId) {
        assert dataSource != null : "Cannot link organization to region without a DataSource"
        String sql = "INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        try (Connection conn = rawDataSource.connection; PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, orgId)
            stmt.setObject(2, regionId)
            stmt.executeUpdate()
        }
    }

    void linkOrganizationToRegion(Organization org, AdministrativeRegion region) {
        linkOrganizationToRegion(org?.id(), region?.id())
    }

    void linkOrganizationToLocation(UUID orgId, UUID locationId) {
        assert dataSource != null : "Cannot link organization to location without a DataSource"
        String sql = "INSERT INTO organization_locations (organization_id, location_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        try (Connection conn = rawDataSource.connection; PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, orgId)
            stmt.setObject(2, locationId)
            stmt.executeUpdate()
        }
    }

    void linkOrganizationToLocation(Organization org, Location location) {
        linkOrganizationToLocation(org?.id(), location?.id())
    }

    void addProjectLocation(UUID projectId, UUID locationId) {
        assert dataSource != null : "Cannot link project to location without a DataSource"
        String sql = "INSERT INTO project_locations (project_id, location_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        try (Connection conn = rawDataSource.connection; PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, projectId)
            stmt.setObject(2, locationId)
            stmt.executeUpdate()
        }
    }

    void addProjectLocation(Project project, Location location) {
        addProjectLocation(project?.id(), location?.id())
    }

    void addProjectBoundary(UUID projectId, UUID boundaryId) {
        assert dataSource != null : "Cannot link project to boundary without a DataSource"
        String sql = "INSERT INTO project_boundaries (project_id, boundary_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        try (Connection conn = rawDataSource.connection; PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, projectId)
            stmt.setObject(2, boundaryId)
            stmt.executeUpdate()
        }
    }

    void addProjectBoundary(Project project, Boundary boundary) {
        addProjectBoundary(project?.id(), boundary?.id())
    }

    void addShiftSignup(UUID shiftId, UUID userId, String status = "REGISTERED") {
        assert dataSource != null : "Cannot add shift signup without a DataSource"
        String sql = "INSERT INTO shift_signups (shift_id, user_id, status) VALUES (?, ?, ?) ON CONFLICT (shift_id, user_id) DO UPDATE SET status = EXCLUDED.status"
        try (Connection conn = rawDataSource.connection; PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, shiftId)
            stmt.setObject(2, userId)
            stmt.setString(3, status)
            stmt.executeUpdate()
        }
    }

    void addShiftSignup(Shift shift, User user, String status = "REGISTERED") {
        addShiftSignup(shift?.id(), user?.id(), status)
    }

    void addShiftTag(UUID shiftId, UUID tagId) {
        assert dataSource != null : "Cannot link shift to tag without a DataSource"
        String sql = "INSERT INTO shift_tags (shift_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        try (Connection conn = rawDataSource.connection; PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, shiftId)
            stmt.setObject(2, tagId)
            stmt.executeUpdate()
        }
    }

    void updateShiftCapacity(UUID shiftId, Integer capacity) {
        assert dataSource != null : "Cannot update shift capacity without a DataSource"
        String sql = "UPDATE shifts SET capacity = ? WHERE id = ?"
        try (Connection conn = rawDataSource.connection; PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (capacity != null) {
                stmt.setInt(1, capacity)
            } else {
                stmt.setNull(1, Types.INTEGER)
            }
            stmt.setObject(2, shiftId)
            stmt.executeUpdate()
        }
    }

    private DataSource getRawDataSource() {
        return DelegatingDataSource.unwrapDataSource(dataSource)
    }

    GeometryFactory getGeometryFactory() {
        return geometryFactory
    }

    Faker getFaker() {
        return faker
    }
}
