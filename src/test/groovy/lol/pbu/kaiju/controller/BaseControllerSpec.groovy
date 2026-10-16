package lol.pbu.kaiju.controller

import groovy.sql.Sql
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
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "true")
@Property(name = "micronaut.security.token.jwt.signatures.secret.generator.secret", value = "pleaseChangeThisSecretForANewOne32Bits")
@MicronautTest(transactional = false)
abstract class BaseControllerSpec extends Specification {

    @Inject
    TokenGenerator tokenGenerator

    @Inject
    @Client("/")
    HttpClient httpClient

    BlockingHttpClient getClient() {
        httpClient.toBlocking()
    }

    @Inject
    @Shared
    DataSource dataSource

    Sql getSql() {
        new Sql(DelegatingDataSource.unwrapDataSource(dataSource))
    }

    protected void executeUpdate(String sqlString, Object... parameters) {
        sql.execute(sqlString, parameters as List)
    }

    protected void ensureGlobalAdmin() {
        executeUpdate("INSERT INTO users (id, email, role) VALUES ('00000000-0000-0000-0000-000000000000', 'global-admin@example.com', 'GLOBAL_ADMIN') ON CONFLICT DO NOTHING")
    }

    def setup() {
        ensureGlobalAdmin()
    }

    protected void cleanupDatabase() {
        String SEEDED_USERS = TestPersonas.SEEDED_PERSONAS.collect { "'${it.id}'" }.join(", ")
        String SEEDED_ORGS = TestPersonas.SEEDED_ORGANIZATION_IDS.collect { "'${it}'" }.join(", ")
        String SEEDED_REGIONS = TestPersonas.SEEDED_REGION_IDS.collect { "'${it}'" }.join(", ")

        executeUpdate("DELETE FROM shift_signups")
        executeUpdate("DELETE FROM shift_tags")
        executeUpdate("DELETE FROM shifts")
        executeUpdate("DELETE FROM project_audit_logs")
        executeUpdate("DELETE FROM project_locations")
        executeUpdate("DELETE FROM project_boundaries")
        executeUpdate("DELETE FROM project_users")
        executeUpdate("DELETE FROM projects")
        executeUpdate("DELETE FROM organization_audit_logs")
        executeUpdate("DELETE FROM organization_regions WHERE organization_id NOT IN (${SEEDED_ORGS}) OR region_id NOT IN (${SEEDED_REGIONS})")
        executeUpdate("DELETE FROM organization_locations")
        executeUpdate("DELETE FROM region_users WHERE region_id NOT IN (${SEEDED_REGIONS})")
        executeUpdate("DELETE FROM organization_users WHERE organization_id NOT IN (${SEEDED_ORGS})")
        executeUpdate("DELETE FROM organizations WHERE id NOT IN (${SEEDED_ORGS})")
        executeUpdate("DELETE FROM locations")
        executeUpdate("DELETE FROM boundaries")
        executeUpdate("DELETE FROM administrative_regions WHERE id NOT IN (${SEEDED_REGIONS})")
        executeUpdate("DELETE FROM tags")
        executeUpdate("DELETE FROM users WHERE id NOT IN (${SEEDED_USERS}) AND email != 'global-admin@example.com'")
        ensureGlobalAdmin()
    }

    def cleanup() {
        cleanupDatabase()
    }

    protected <T> MutableHttpRequest<T> asPersona(MutableHttpRequest<T> request, TestPersonas.Persona persona) {
        Map<String, Object> claims = [
                "sub"               : persona.id.toString(),
                "email"             : persona.email,
                "preferred_username": persona.displayName,
                "email_verified"    : true,
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
        Map<String, Object> claims = [
                "sub": userId,
                "roles": roles
        ]
        String token = tokenGenerator.generateToken(claims).orElseThrow()
        request.bearerAuth(token)
        return request
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
