package lol.pbu.kaiju.security

import groovy.sql.Sql
import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.OrganizationUserRole
import lol.pbu.kaiju.model.RegionUserRole
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.service.UserService
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import spock.lang.Specification
import spock.lang.Unroll

import javax.sql.DataSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@MicronautTest(transactional = true)
class CommunityPersonaAuthenticationSpec extends Specification {

    @Inject
    UserRepository userRepository

    @Inject
    UserService userService

    @Inject
    AuthentikAuthenticationMapper mapper

    @Inject
    DataSource dataSource

    private static <T> T await(Publisher<T> publisher) {
        CompletableFuture<T> future = new CompletableFuture<>()
        publisher.subscribe(new Subscriber<T>() {
            @Override
            void onSubscribe(Subscription s) {
                s.request(1)
            }
            @Override
            void onNext(T t) {
                future.complete(t)
            }
            @Override
            void onError(Throwable t) {
                future.completeExceptionally(t)
            }
            @Override
            void onComplete() {
                if (!future.isDone()) {
                    future.complete(null)
                }
            }
        })
        return future.get(5, TimeUnit.SECONDS)
    }

    def "all 26 seeded personas exist in database with correct role"() {
        expect: "each seeded persona is present in PostgreSQL with their designated system role"
        TestPersonas.SEEDED_PERSONAS.each { persona ->
            Optional<User> userOpt = userRepository.findById(persona.id)
            assert userOpt.isPresent(): "User not found for ${persona.displayName} (${persona.id})"
            User user = userOpt.get()
            assert user.email() == persona.email
            assert user.role() == persona.systemRole
        }
    }

    def "all seeded regions in 3-tier regional hierarchy exist with correct parentage"() {
        when: "querying administrative regions from the database"
        Map<UUID, Map<String, Object>> regions = [:]
        new Sql(dataSource).eachRow("SELECT id, name, parent_region_id FROM administrative_regions") { row ->
            UUID id = row.id as UUID
            String name = row.name as String
            UUID parentId = row.parent_region_id ? (row.parent_region_id as UUID) : null
            regions[id] = [name: name, parentId: parentId]
        }

        then: "Level 1 Colorado State Region exists with no parent"
        regions.containsKey(TestPersonas.COLORADO_STATE_REGION_ID)
        regions[TestPersonas.COLORADO_STATE_REGION_ID].name == "Colorado State Region"
        regions[TestPersonas.COLORADO_STATE_REGION_ID].parentId == null

        and: "Level 2 Denver County and El Paso County regions point to Colorado State Region"
        regions.containsKey(TestPersonas.DENVER_COUNTY_REGION_ID)
        regions[TestPersonas.DENVER_COUNTY_REGION_ID].name == "Denver County Region"
        regions[TestPersonas.DENVER_COUNTY_REGION_ID].parentId == TestPersonas.COLORADO_STATE_REGION_ID

        regions.containsKey(TestPersonas.EL_PASO_COUNTY_REGION_ID)
        regions[TestPersonas.EL_PASO_COUNTY_REGION_ID].name == "El Paso County Region"
        regions[TestPersonas.EL_PASO_COUNTY_REGION_ID].parentId == TestPersonas.COLORADO_STATE_REGION_ID

        and: "Level 3 Downtown Core regions point to their respective county regions"
        regions.containsKey(TestPersonas.DENVER_DOWNTOWN_REGION_ID)
        regions[TestPersonas.DENVER_DOWNTOWN_REGION_ID].name == "Denver Downtown Core"
        regions[TestPersonas.DENVER_DOWNTOWN_REGION_ID].parentId == TestPersonas.DENVER_COUNTY_REGION_ID

        regions.containsKey(TestPersonas.COLORADO_SPRINGS_DOWNTOWN_REGION_ID)
        regions[TestPersonas.COLORADO_SPRINGS_DOWNTOWN_REGION_ID].name == "Colorado Springs Downtown"
        regions[TestPersonas.COLORADO_SPRINGS_DOWNTOWN_REGION_ID].parentId == TestPersonas.EL_PASO_COUNTY_REGION_ID
    }

    def "all seeded organizations in 3-tier organizational hierarchy exist with correct attributes"() {
        when: "querying organizations from the database"
        Map<UUID, Map<String, Object>> orgs = [:]
        new Sql(dataSource).eachRow("SELECT id, name, parent_id, is_public, verification_status FROM organizations") { row ->
            UUID id = row.id as UUID
            String name = row.name as String
            UUID parentId = row.parent_id ? (row.parent_id as UUID) : null
            boolean isPublic = row.is_public as Boolean
            String status = row.verification_status as String
            orgs[id] = [name: name, parentId: parentId, isPublic: isPublic, status: status]
        }

        then: "Level 1 National Relief Network is public, verified, and has no parent"
        orgs.containsKey(TestPersonas.NATIONAL_RELIEF_NETWORK_ID)
        orgs[TestPersonas.NATIONAL_RELIEF_NETWORK_ID].name == "National Relief Network"
        orgs[TestPersonas.NATIONAL_RELIEF_NETWORK_ID].parentId == null
        orgs[TestPersonas.NATIONAL_RELIEF_NETWORK_ID].isPublic == true
        orgs[TestPersonas.NATIONAL_RELIEF_NETWORK_ID].status == "VERIFIED"

        and: "Level 2 State Chapters point to National Relief Network and are verified"
        orgs.containsKey(TestPersonas.COLORADO_STATE_CHAPTER_ID)
        orgs[TestPersonas.COLORADO_STATE_CHAPTER_ID].parentId == TestPersonas.NATIONAL_RELIEF_NETWORK_ID
        orgs[TestPersonas.COLORADO_STATE_CHAPTER_ID].status == "VERIFIED"

        orgs.containsKey(TestPersonas.UTAH_STATE_CHAPTER_ID)
        orgs[TestPersonas.UTAH_STATE_CHAPTER_ID].parentId == TestPersonas.NATIONAL_RELIEF_NETWORK_ID
        orgs[TestPersonas.UTAH_STATE_CHAPTER_ID].status == "VERIFIED"

        and: "Level 3 Local Branches point to Colorado State Chapter and are verified"
        orgs.containsKey(TestPersonas.DENVER_METRO_BRANCH_ID)
        orgs[TestPersonas.DENVER_METRO_BRANCH_ID].parentId == TestPersonas.COLORADO_STATE_CHAPTER_ID
        orgs[TestPersonas.DENVER_METRO_BRANCH_ID].status == "VERIFIED"

        orgs.containsKey(TestPersonas.COLORADO_SPRINGS_BRANCH_ID)
        orgs[TestPersonas.COLORADO_SPRINGS_BRANCH_ID].parentId == TestPersonas.COLORADO_STATE_CHAPTER_ID
        orgs[TestPersonas.COLORADO_SPRINGS_BRANCH_ID].status == "VERIFIED"

        and: "Standalone Local Grassroots Co-op has no parent and is unverified"
        orgs.containsKey(TestPersonas.LOCAL_GRASSROOTS_COOP_ID)
        orgs[TestPersonas.LOCAL_GRASSROOTS_COOP_ID].parentId == null
        orgs[TestPersonas.LOCAL_GRASSROOTS_COOP_ID].status == "UNVERIFIED"
    }

    def "region_users and organization_users bindings match expected persona assignments"() {
        when: "querying region_users bindings"
        Set<String> regionBindings = new HashSet<>()
        Sql sql = new Sql(dataSource)
        sql.eachRow("SELECT user_id, region_id, role FROM region_users") { row ->
            regionBindings.add("${row.user_id}:${row.region_id}:${row.role}".toString())
        }

        and: "querying organization_users bindings"
        Set<String> orgBindings = new HashSet<>()
        sql.eachRow("SELECT user_id, organization_id, role FROM organization_users") { row ->
            orgBindings.add("${row.user_id}:${row.organization_id}:${row.role}".toString())
        }

        then: "all regional persona bindings are present"
        TestPersonas.SEEDED_PERSONAS.findAll { it.regionId != null }.each { persona ->
            String expected = "${persona.id}:${persona.regionId}:${persona.regionRole.name()}".toString()
            assert regionBindings.contains(expected): "Missing region binding for ${persona.displayName}: expected ${expected}"
        }

        and: "all organization persona bindings are present"
        TestPersonas.SEEDED_PERSONAS.findAll { it.organizationId != null }.each { persona ->
            String expected = "${persona.id}:${persona.organizationId}:${persona.organizationRole.name()}".toString()
            assert orgBindings.contains(expected): "Missing org binding for ${persona.displayName}: expected ${expected}"
        }
    }

    @Unroll
    def "Authentik mapper authenticates persona '#persona.displayName' and assigns correct claims"() {
        given: "an OpenID token containing the persona email"
        OpenIdClaims claims = [getEmail: { -> persona.email }] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "AuthentikAuthenticationMapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication succeeds with user ID as name and full permission claims"
        response.isAuthenticated()
        def auth = response.getAuthentication().get()
        auth.getName() == persona.id.toString()
        auth.getAttributes().get("email") == persona.email

        and: "all permissions mapped to the persona role are granted as claims"
        persona.systemRole.getPermissions().each { permission ->
            assert auth.getRoles().contains(permission.getClaim()):
                    "Missing claim ${permission.getClaim()} for persona ${persona.displayName}"
        }

        where:
        persona << TestPersonas.SEEDED_PERSONAS
    }

    def "JIT provisioning successfully creates jenny.new@kaiju.local in PostgreSQL as STANDARD_USER"() {
        given: "an unseeded persona Jenny New who exists only in Authentik"
        def jenny = TestPersonas.JENNY_NEW
        assert !userRepository.findByEmail(jenny.email).isPresent(): "Jenny should not initially exist in DB"

        and: "an incoming token for Jenny"
        OpenIdClaims claims = [getEmail: { -> jenny.email }] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the Authentik mapper processes Jenny's initial login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication succeeds and returns a valid session"
        response.isAuthenticated()
        def auth = response.getAuthentication().get()
        auth.getAttributes().get("email") == jenny.email

        and: "Jenny was created in PostgreSQL as a STANDARD_USER"
        def dbUser = userRepository.findByEmail(jenny.email).get()
        dbUser.email() == jenny.email
        dbUser.role() == UserRole.STANDARD_USER
        auth.getName() == dbUser.id().toString()

        and: "STANDARD_USER claims are assigned"
        UserRole.STANDARD_USER.getPermissions().each { permission ->
            assert auth.getRoles().contains(permission.getClaim())
        }
    }

    def "should reject authentication when email is missing from claims"() {
        given: "an incoming token without an email"
        OpenIdClaims claims = [getEmail: { -> null }] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the request"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication fails with an informative error message"
        !response.isAuthenticated()
        response.getMessage().get() == "No email present in OpenID claims"
    }

    def "should reject authentication when email is blank in claims"() {
        given: "an incoming token with whitespace email"
        OpenIdClaims claims = [getEmail: { -> "   " }] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the request"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication fails with an informative error message"
        !response.isAuthenticated()
        response.getMessage().get() == "No email present in OpenID claims"
    }
}
