package lol.pbu.kaiju.fixtures.builder

import jakarta.inject.Inject
import jakarta.inject.Singleton
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Boundary
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.OrganizationUser
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.RegionUser
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.domain.Tag
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.ProjectStatus
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
import java.util.concurrent.ConcurrentHashMap

@Singleton
class KaijuDataBuilder {

    final Set<UUID> trackedShiftIds = ConcurrentHashMap.newKeySet()
    final Set<UUID> trackedProjectIds = ConcurrentHashMap.newKeySet()
    final Set<UUID> trackedOrganizationIds = ConcurrentHashMap.newKeySet()
    final Set<UUID> trackedRegionIds = ConcurrentHashMap.newKeySet()
    final Set<UUID> trackedLocationIds = ConcurrentHashMap.newKeySet()
    final Set<UUID> trackedBoundaryIds = ConcurrentHashMap.newKeySet()
    final Set<UUID> trackedTagIds = ConcurrentHashMap.newKeySet()
    final Set<UUID> trackedUserIds = ConcurrentHashMap.newKeySet()
    final Set<Map<String, UUID>> trackedOrgUsers = ConcurrentHashMap.newKeySet()
    final Set<Map<String, UUID>> trackedRegionUsers = ConcurrentHashMap.newKeySet()
    final Set<Map<String, UUID>> trackedShiftSignups = ConcurrentHashMap.newKeySet()
    final Set<Map<String, UUID>> trackedShiftTags = ConcurrentHashMap.newKeySet()
    final Set<Map<String, UUID>> trackedProjectLocations = ConcurrentHashMap.newKeySet()
    final Set<Map<String, UUID>> trackedProjectBoundaries = ConcurrentHashMap.newKeySet()
    final Set<Map<String, UUID>> trackedOrgRegions = ConcurrentHashMap.newKeySet()
    final Set<Map<String, UUID>> trackedOrgLocations = ConcurrentHashMap.newKeySet()

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
        return new UserBuilder(userRepository, faker, this)
    }

    RegionBuilder region() {
        return new RegionBuilder(regionRepository, geometryFactory, faker, this)
    }

    LocationBuilder location() {
        return new LocationBuilder(locationRepository, geometryFactory, faker, this)
    }

    TagBuilder tag() {
        return new TagBuilder(tagRepository, this)
    }

    BoundaryBuilder boundary() {
        return new BoundaryBuilder(boundaryRepository, geometryFactory, this)
    }

    OrganizationBuilder organization() {
        return new OrganizationBuilder(organizationRepository, this, faker)
    }

    OrganizationUserBuilder organizationUser() {
        return new OrganizationUserBuilder(organizationUserRepository, this)
    }

    RegionUserBuilder regionUser() {
        return new RegionUserBuilder(regionUserRepository, this)
    }

    ProjectBuilder project() {
        return new ProjectBuilder(projectRepository, this, faker)
    }

    ShiftBuilder shift() {
        return new ShiftBuilder(shiftRepository, this, faker)
    }

    void trackUser(UUID id) {
        if (id != null && !TestPersonas.SEEDED_PERSONA_IDS.contains(id) && id != TestPersonas.GLOBAL_ADMIN_ID) {
            trackedUserIds.add(id)
        }
    }

    void trackOrganization(UUID id) {
        if (id != null && !TestPersonas.SEEDED_ORGANIZATION_IDS.contains(id)) {
            trackedOrganizationIds.add(id)
        }
    }

    void trackRegion(UUID id) {
        if (id != null && !TestPersonas.SEEDED_REGION_IDS.contains(id)) {
            trackedRegionIds.add(id)
        }
    }

    void trackLocation(UUID id) {
        if (id != null) {
            trackedLocationIds.add(id)
        }
    }

    void trackTag(UUID id) {
        if (id != null) {
            trackedTagIds.add(id)
        }
    }

    void trackBoundary(UUID id) {
        if (id != null) {
            trackedBoundaryIds.add(id)
        }
    }

    void trackProject(UUID id) {
        if (id != null) {
            trackedProjectIds.add(id)
        }
    }

    void trackShift(UUID id) {
        if (id != null) {
            trackedShiftIds.add(id)
        }
    }

    void trackOrganizationUser(UUID userId, UUID orgId) {
        if (userId != null && orgId != null) {
            def persona = TestPersonas.findById(userId)
            if (persona != null && persona.isSeededInDb && persona.organizationId == orgId) {
                return
            }
            trackedOrgUsers.add([userId: userId, organizationId: orgId])
        }
    }

    void trackRegionUser(UUID userId, UUID regionId) {
        if (userId != null && regionId != null) {
            def persona = TestPersonas.findById(userId)
            if (persona != null && persona.isSeededInDb && persona.regionId == regionId) {
                return
            }
            trackedRegionUsers.add([userId: userId, regionId: regionId])
        }
    }

    void trackShiftSignup(UUID shiftId, UUID userId) {
        if (shiftId != null && userId != null) {
            trackedShiftSignups.add([shiftId: shiftId, userId: userId])
        }
    }

    void trackShiftTag(UUID shiftId, UUID tagId) {
        if (shiftId != null && tagId != null) {
            trackedShiftTags.add([shiftId: shiftId, tagId: tagId])
        }
    }

    void trackProjectLocation(UUID projectId, UUID locationId) {
        if (projectId != null && locationId != null) {
            trackedProjectLocations.add([projectId: projectId, locationId: locationId])
        }
    }

    void trackProjectBoundary(UUID projectId, UUID boundaryId) {
        if (projectId != null && boundaryId != null) {
            trackedProjectBoundaries.add([projectId: projectId, boundaryId: boundaryId])
        }
    }

    void trackOrganizationRegion(UUID orgId, UUID regionId) {
        if (orgId != null && regionId != null) {
            if (TestPersonas.SEEDED_ORGANIZATION_IDS.contains(orgId) && TestPersonas.SEEDED_REGION_IDS.contains(regionId)) {
                return
            }
            trackedOrgRegions.add([organizationId: orgId, regionId: regionId])
        }
    }

    void trackOrganizationLocation(UUID orgId, UUID locationId) {
        if (orgId != null && locationId != null) {
            trackedOrgLocations.add([organizationId: orgId, locationId: locationId])
        }
    }

    <T> T track(T entity) {
        if (entity == null) {
            return null
        }
        if (entity instanceof Project) {
            trackProject(entity.id())
        } else if (entity instanceof Shift) {
            trackShift(entity.id())
        } else if (entity instanceof Organization) {
            trackOrganization(entity.id())
        } else if (entity instanceof User) {
            trackUser(entity.id())
        } else if (entity instanceof Location) {
            trackLocation(entity.id())
        } else if (entity instanceof Tag) {
            trackTag(entity.id())
        } else if (entity instanceof Boundary) {
            trackBoundary(entity.id())
        } else if (entity instanceof AdministrativeRegion) {
            trackRegion(entity.id())
        } else if (entity instanceof OrganizationUser) {
            trackOrganizationUser(entity.id().userId(), entity.id().organizationId())
        } else if (entity instanceof RegionUser) {
            trackRegionUser(entity.id().userId(), entity.id().regionId())
        }
        return entity
    }

    void linkOrganizationToRegion(UUID orgId, UUID regionId) {
        assert dataSource != null : "Cannot link organization to region without a DataSource"
        String sql = "INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        try (Connection conn = rawDataSource.connection; PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, orgId)
            stmt.setObject(2, regionId)
            stmt.executeUpdate()
        }
        trackOrganizationRegion(orgId, regionId)
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
        trackOrganizationLocation(orgId, locationId)
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
        trackProjectLocation(projectId, locationId)
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
        trackProjectBoundary(projectId, boundaryId)
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
        trackShiftSignup(shiftId, userId)
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
        trackShiftTag(shiftId, tagId)
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

    void updateProjectStatus(UUID projectId, ProjectStatus status) {
        assert dataSource != null : "Cannot update project status without a DataSource"
        String sql = "UPDATE projects SET status = ? WHERE id = ?"
        try (Connection conn = rawDataSource.connection; PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status?.name() ?: ProjectStatus.PENDING.name())
            stmt.setObject(2, projectId)
            stmt.executeUpdate()
        }
    }

    void cleanupTrackedEntities() {
        try {
            if (dataSource == null) {
                return
            }
            try (Connection conn = rawDataSource.connection) {
                deletePairs(conn, "DELETE FROM shift_signups WHERE shift_id = ? AND user_id = ?", trackedShiftSignups, "shiftId", "userId")
                deletePairs(conn, "DELETE FROM shift_tags WHERE shift_id = ? AND tag_id = ?", trackedShiftTags, "shiftId", "tagId")
                deleteSingle(conn, "DELETE FROM shifts WHERE id = ?", trackedShiftIds)
                deleteSingle(conn, "DELETE FROM project_audit_logs WHERE project_id = ?", trackedProjectIds)
                deletePairs(conn, "DELETE FROM project_locations WHERE project_id = ? AND location_id = ?", trackedProjectLocations, "projectId", "locationId")
                deletePairs(conn, "DELETE FROM project_boundaries WHERE project_id = ? AND boundary_id = ?", trackedProjectBoundaries, "projectId", "boundaryId")
                deleteSingle(conn, "DELETE FROM project_users WHERE project_id = ?", trackedProjectIds)
                deleteSingle(conn, "DELETE FROM projects WHERE id = ?", trackedProjectIds)
                deleteSingle(conn, "DELETE FROM organization_audit_logs WHERE organization_id = ?", trackedOrganizationIds)
                deletePairs(conn, "DELETE FROM organization_regions WHERE organization_id = ? AND region_id = ?", trackedOrgRegions, "organizationId", "regionId")
                deletePairs(conn, "DELETE FROM organization_locations WHERE organization_id = ? AND location_id = ?", trackedOrgLocations, "organizationId", "locationId")
                deletePairs(conn, "DELETE FROM region_users WHERE user_id = ? AND region_id = ?", trackedRegionUsers, "userId", "regionId")
                deletePairs(conn, "DELETE FROM organization_users WHERE user_id = ? AND organization_id = ?", trackedOrgUsers, "userId", "organizationId")
                executeSingle(conn, "UPDATE organizations SET parent_id = NULL WHERE id = ?", trackedOrganizationIds)
                deleteSingle(conn, "DELETE FROM organizations WHERE id = ?", trackedOrganizationIds)
                deleteSingle(conn, "DELETE FROM locations WHERE id = ?", trackedLocationIds)
                deleteSingle(conn, "DELETE FROM boundaries WHERE id = ?", trackedBoundaryIds)
                executeSingle(conn, "UPDATE administrative_regions SET parent_region_id = NULL WHERE id = ?", trackedRegionIds)
                deleteSingle(conn, "DELETE FROM administrative_regions WHERE id = ?", trackedRegionIds)
                deleteSingle(conn, "DELETE FROM tags WHERE id = ?", trackedTagIds)
                deleteSingle(conn, "DELETE FROM project_audit_logs WHERE actor_id = ?", trackedUserIds)
                deleteSingle(conn, "DELETE FROM organization_audit_logs WHERE actor_id = ?", trackedUserIds)
                deleteSingle(conn, "DELETE FROM users WHERE id = ?", trackedUserIds)
            }
        } finally {
            clearTrackedEntities()
        }
    }

    void clearTrackedEntities() {
        trackedShiftIds.clear()
        trackedProjectIds.clear()
        trackedOrganizationIds.clear()
        trackedRegionIds.clear()
        trackedLocationIds.clear()
        trackedBoundaryIds.clear()
        trackedTagIds.clear()
        trackedUserIds.clear()
        trackedOrgUsers.clear()
        trackedRegionUsers.clear()
        trackedShiftSignups.clear()
        trackedShiftTags.clear()
        trackedProjectLocations.clear()
        trackedProjectBoundaries.clear()
        trackedOrgRegions.clear()
        trackedOrgLocations.clear()
    }

    private void executeSingle(Connection conn, String sql, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return
        }
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (UUID id : ids) {
                stmt.setObject(1, id)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private void deleteSingle(Connection conn, String sql, Set<UUID> ids) {
        executeSingle(conn, sql, ids)
    }

    private void deletePairs(Connection conn, String sql, Set<Map<String, UUID>> pairs, String key1, String key2) {
        if (pairs.isEmpty()) {
            return
        }
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Map<String, UUID> pair : pairs) {
                stmt.setObject(1, pair[key1])
                stmt.setObject(2, pair[key2])
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    DataSource getRawDataSource() {
        return DelegatingDataSource.unwrapDataSource(dataSource)
    }

    GeometryFactory getGeometryFactory() {
        return geometryFactory
    }

    Faker getFaker() {
        return faker
    }
}
