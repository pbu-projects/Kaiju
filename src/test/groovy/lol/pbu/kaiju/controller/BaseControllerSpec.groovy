package lol.pbu.kaiju.controller

import groovy.sql.Sql
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.publisher.Publishers
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.filters.AuthenticationFetcher
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.reactivestreams.Publisher
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
abstract class BaseControllerSpec extends Specification {

    @Singleton
    @Requires(env = "test")
    static class HeaderAuthenticationFetcher implements AuthenticationFetcher<HttpRequest<?>> {
        @Override
        Publisher<Authentication> fetchAuthentication(HttpRequest<?> request) {
            String testUser = request.getHeaders().get("X-Test-User")
            if (testUser) {
                List<String> roles = request.getHeaders().getAll("X-Test-Role")
                return Publishers.just(Authentication.build(testUser, roles))
            }
            return Publishers.empty()
        }
    }

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
        executeUpdate("""
            TRUNCATE TABLE 
                project_audit_logs,
                organization_audit_logs,
                shift_signups,
                shift_tags,
                shifts,
                project_locations,
                project_boundaries,
                project_users,
                projects,
                organization_regions,
                organization_locations,
                organization_users,
                region_users,
                organizations,
                locations,
                boundaries,
                administrative_regions,
                tags,
                users
            CASCADE
        """)
        ensureGlobalAdmin()
    }

    def cleanup() {
        cleanupDatabase()
    }

    protected <T> MutableHttpRequest<T> authenticated(MutableHttpRequest<T> request, String userId, List<String> roles) {
        request.header("X-Test-User", userId)
        roles.each { role -> request.header("X-Test-Role", role) }
        return request
    }

    protected <T> MutableHttpRequest<T> asGlobalAdmin(MutableHttpRequest<T> request, String userId = "00000000-0000-0000-0000-000000000000") {
        authenticated(request, userId, ["GLOBAL_ADMIN", "system:admin", "system:user:manage", "project:approve", "project:manage", "region:manage", "org:manage_users", "org:edit"])
    }
}
