package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.fixtures.builder.KaijuDataBuilder
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.security.Principal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "true")
@Property(name = "micronaut.security.token.jwt.signatures.secret.generator.secret", value = "pleaseChangeThisSecretForANewOne32Bits")
@MicronautTest(transactional = false)
abstract class BaseControllerSpec extends Specification {

    public static final String TEST_ISSUER = "http://localhost:9000/application/o/kaiju/"
    public static final String TEST_AUDIENCE = "kaiju-client"

    @Inject
    TokenGenerator tokenGenerator

    @Inject
    KaijuDataBuilder dataBuilder

    @Inject
    @Client("/")
    HttpClient httpClient

    BlockingHttpClient getClient() {
        httpClient.toBlocking()
    }

    @Inject
    @Shared
    DataSource dataSource

    protected DataSource getRawDataSource() {
        DelegatingDataSource.unwrapDataSource(dataSource)
    }

    protected void ensureGlobalAdmin() {
        String sql = "INSERT INTO users (id, email, role) VALUES ('00000000-0000-0000-0000-000000000000', 'global-admin@example.com', 'GLOBAL_ADMIN') ON CONFLICT DO NOTHING"
        try (Connection conn = rawDataSource.connection; PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate()
        }
    }

    def setup() {
        ensureGlobalAdmin()
    }

    protected void cleanupDatabase() {
        dataBuilder.cleanupTrackedEntities()
        ensureGlobalAdmin()
    }

    def cleanup() {
        dataBuilder.cleanupTrackedEntities()
    }

    protected <T> T track(T entity) {
        dataBuilder.track(entity)
        return entity
    }

    protected void trackProject(UUID id) {
        dataBuilder.trackProject(id)
    }

    protected void trackShift(UUID id) {
        dataBuilder.trackShift(id)
    }

    protected void trackOrganization(UUID id) {
        dataBuilder.trackOrganization(id)
    }

    protected void trackUser(UUID id) {
        dataBuilder.trackUser(id)
    }

    protected void trackLocation(UUID id) {
        dataBuilder.trackLocation(id)
    }

    protected void trackRegion(UUID id) {
        dataBuilder.trackRegion(id)
    }

    protected void trackTag(UUID id) {
        dataBuilder.trackTag(id)
    }

    protected void trackBoundary(UUID id) {
        dataBuilder.trackBoundary(id)
    }

    protected void trackOrganizationUser(UUID userId, UUID orgId) {
        dataBuilder.trackOrganizationUser(userId, orgId)
    }

    protected void trackRegionUser(UUID userId, UUID regionId) {
        dataBuilder.trackRegionUser(userId, regionId)
    }

    protected <T> MutableHttpRequest<T> asPersona(MutableHttpRequest<T> request, TestPersonas.Persona persona) {
        Map<String, Object> claims = [
                "sub"               : persona.id.toString(),
                "email"             : persona.email,
                "preferred_username": persona.displayName,
                "email_verified"    : true,
                "iss"               : TEST_ISSUER,
                "aud"               : TEST_AUDIENCE,
                "roles"             : [persona.systemRole.name()]
        ]
        String token = tokenGenerator.generateToken(claims).orElseThrow()
        request.bearerAuth(token)
        return request
    }

    protected <T> MutableHttpRequest<T> authenticated(MutableHttpRequest<T> request, TestPersonas.Persona persona) {
        return asPersona(request, persona)
    }

    protected <T> MutableHttpRequest<T> authenticated(MutableHttpRequest<T> request, String userId, List<String> roles) {
        String email = null
        try (Connection conn = rawDataSource.connection;
             PreparedStatement stmt = conn.prepareStatement("SELECT email FROM users WHERE id = ?::uuid")) {
            stmt.setObject(1, userId)
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    email = rs.getString("email")
                }
            }
        } catch (Exception ignored) {
        }
        if (!email) {
            def persona = TestPersonas.SEEDED_PERSONAS.find { it.id.toString() == userId }
            if (persona) {
                email = persona.email
            } else {
                email = "user-${userId}@kaiju.local"
                String roleToSet = roles ? roles.find { ["GLOBAL_ADMIN", "REGION_DIRECTOR", "REGION_AGENT", "ORG_ADMIN", "ORG_MANAGER", "ORG_MEMBER", "STANDARD_USER"].contains(it) } ?: "STANDARD_USER" : "STANDARD_USER"
                try (Connection conn = rawDataSource.connection;
                     PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (id, email, role) VALUES (?::uuid, ?, ?) ON CONFLICT (id) DO UPDATE SET role = EXCLUDED.role")) {
                    stmt.setObject(1, userId)
                    stmt.setString(2, email)
                    stmt.setString(3, roleToSet)
                    stmt.executeUpdate()
                } catch (Exception ignored) {
                }
                try {
                    trackUser(UUID.fromString(userId))
                } catch (Exception ignored) {
                }
            }
        }

        Map<String, Object> claims = [
                "sub"           : userId,
                "email"         : email,
                "email_verified": true,
                "iss"           : TEST_ISSUER,
                "aud"           : TEST_AUDIENCE,
                "roles"         : roles
        ]
        String token = tokenGenerator.generateToken(claims).orElseThrow()
        request.bearerAuth(token)
        return request
    }

    protected Principal createPrincipal(UUID userId) {
        return ({ userId.toString() } as Principal)
    }

    protected <T> MutableHttpRequest<T> asGlobalAdmin(MutableHttpRequest<T> request, TestPersonas.Persona persona = TestPersonas.SARAH_ADMIN) {
        asPersona(request, persona)
    }

    protected <T> MutableHttpRequest<T> asGlobalAdmin(MutableHttpRequest<T> request, String userId) {
        authenticated(request, userId, ["GLOBAL_ADMIN", "system:admin", "system:user:manage", "project:approve", "project:manage", "region:manage", "org:manage_users", "org:edit"])
    }

    protected <T> MutableHttpRequest<T> asRegionDirector(MutableHttpRequest<T> request, TestPersonas.Persona persona = TestPersonas.DANIEL_COUNTY_DIRECTOR) {
        asPersona(request, persona)
    }

    protected <T> MutableHttpRequest<T> asRegionDirector(MutableHttpRequest<T> request, String userId) {
        authenticated(request, userId, ["REGION_DIRECTOR", "region:manage", "project:approve", "project:reassign", "project:create", "project:read", "project:update", "project:delete", "org:read"])
    }

    protected <T> MutableHttpRequest<T> asRegionAgent(MutableHttpRequest<T> request, TestPersonas.Persona persona = TestPersonas.CONNIE_COUNTY_AGENT) {
        asPersona(request, persona)
    }

    protected <T> MutableHttpRequest<T> asRegionAgent(MutableHttpRequest<T> request, String userId) {
        authenticated(request, userId, ["REGION_AGENT", "project:approve", "project:reassign", "project:create", "project:read", "project:update", "project:delete", "org:read"])
    }

    protected <T> MutableHttpRequest<T> asOrgAdmin(MutableHttpRequest<T> request, TestPersonas.Persona persona = TestPersonas.LEO_LOCAL_ADMIN) {
        asPersona(request, persona)
    }

    protected <T> MutableHttpRequest<T> asOrgManager(MutableHttpRequest<T> request, TestPersonas.Persona persona = TestPersonas.LAURA_LOCAL_MANAGER) {
        asPersona(request, persona)
    }

    protected <T> MutableHttpRequest<T> asOrgMember(MutableHttpRequest<T> request, TestPersonas.Persona persona = TestPersonas.LUKE_LOCAL_MEMBER) {
        asPersona(request, persona)
    }

    protected <T> MutableHttpRequest<T> asVolunteer(MutableHttpRequest<T> request, TestPersonas.Persona persona = TestPersonas.VICTOR_VOLUNTEER) {
        asPersona(request, persona)
    }

    protected <T> MutableHttpRequest<T> asStandardUser(MutableHttpRequest<T> request, TestPersonas.Persona persona = TestPersonas.VICTOR_VOLUNTEER) {
        asPersona(request, persona)
    }

    protected <T> MutableHttpRequest<T> asStandardUser(MutableHttpRequest<T> request, String userId) {
        authenticated(request, userId, ["STANDARD_USER"])
    }
}
