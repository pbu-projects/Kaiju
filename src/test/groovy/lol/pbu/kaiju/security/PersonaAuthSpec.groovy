package lol.pbu.kaiju.security

import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.OrganizationUserRepository
import lol.pbu.kaiju.repository.RegionUserRepository
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.service.UserService
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import spock.lang.Specification
import spock.lang.Unroll

import javax.sql.DataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@MicronautTest(transactional = true)
class PersonaAuthSpec extends Specification {

    @Inject
    UserRepository userRepository

    @Inject
    AdministrativeRegionRepository administrativeRegionRepository

    @Inject
    OrganizationRepository organizationRepository

    @Inject
    RegionUserRepository regionUserRepository

    @Inject
    OrganizationUserRepository organizationUserRepository

    @Inject
    UserService userService

    @Inject
    AuthentikAuthenticationMapper mapper

    @Inject
    DataSource dataSource

    static <T> T await(Publisher<T> publisher) {
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

    List<Map<String, Object>> executeQuery(String sql, List<Object> params = []) {
        List<Map<String, Object>> results = []
        try (Connection conn = dataSource.connection;
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            params.eachWithIndex { param, idx ->
                stmt.setObject(idx + 1, param)
            }
            try (ResultSet rs = stmt.executeQuery()) {
                int colCount = rs.metaData.columnCount
                while (rs.next()) {
                    results.add(readRow(rs, colCount))
                }
            }
        }
        return results
    }

    static Map<String, Object> readRow(ResultSet rs, int colCount) {
        Map<String, Object> row = [:]
        for (int i = 1; i <= colCount; i++) {
            row[rs.metaData.getColumnLabel(i)] = rs.getObject(i)
        }
        return row
    }

    def "all 26 seeded personas exist in database with correct role"() {
        expect: "each seeded persona is present in PostgreSQL with their designated system role"
        TestPersonas.SEEDED_PERSONAS.each { persona ->
            Optional<User> userOpt = userRepository.findById(persona.id)
            assert userOpt.isPresent(): "User not found for ${persona.displayName} (${persona.id})"
            verifyAll(userOpt.get()) {
                email() == persona.email
                role() == persona.systemRole
            }
        }
    }

    def "all seeded regions in 3-tier regional hierarchy exist with correct parentage"() {
        when: "querying administrative regions from the database"
        Map<UUID, Map<String, Object>> regions = [:]
        administrativeRegionRepository.findAll().each { region ->
            regions[region.id()] = [name: region.name(), parentId: region.parentRegionId()]
        }

        then: "Level 1, Level 2, and Level 3 regions exist with correct parentage and total count matches"
        verifyAll {
            regions.containsKey(TestPersonas.COLORADO_STATE_REGION_ID)
            regions[TestPersonas.COLORADO_STATE_REGION_ID].name == "Colorado State Region"
            regions[TestPersonas.COLORADO_STATE_REGION_ID].parentId == null

            regions.containsKey(TestPersonas.DENVER_COUNTY_REGION_ID)
            regions[TestPersonas.DENVER_COUNTY_REGION_ID].name == "Denver County Region"
            regions[TestPersonas.DENVER_COUNTY_REGION_ID].parentId == TestPersonas.COLORADO_STATE_REGION_ID

            regions.containsKey(TestPersonas.EL_PASO_COUNTY_REGION_ID)
            regions[TestPersonas.EL_PASO_COUNTY_REGION_ID].name == "El Paso County Region"
            regions[TestPersonas.EL_PASO_COUNTY_REGION_ID].parentId == TestPersonas.COLORADO_STATE_REGION_ID

            regions.containsKey(TestPersonas.DENVER_DOWNTOWN_REGION_ID)
            regions[TestPersonas.DENVER_DOWNTOWN_REGION_ID].name == "Denver Downtown Core"
            regions[TestPersonas.DENVER_DOWNTOWN_REGION_ID].parentId == TestPersonas.DENVER_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.COLORADO_SPRINGS_DOWNTOWN_REGION_ID)
            regions[TestPersonas.COLORADO_SPRINGS_DOWNTOWN_REGION_ID].name == "Colorado Springs Downtown"
            regions[TestPersonas.COLORADO_SPRINGS_DOWNTOWN_REGION_ID].parentId == TestPersonas.EL_PASO_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.DENVER_CITY_REGION_ID)
            regions[TestPersonas.DENVER_CITY_REGION_ID].name == "Denver City"
            regions[TestPersonas.DENVER_CITY_REGION_ID].parentId == TestPersonas.DENVER_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.COLORADO_SPRINGS_CITY_REGION_ID)
            regions[TestPersonas.COLORADO_SPRINGS_CITY_REGION_ID].name == "Colorado Springs City"
            regions[TestPersonas.COLORADO_SPRINGS_CITY_REGION_ID].parentId == TestPersonas.EL_PASO_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.FOUNTAIN_CITY_REGION_ID)
            regions[TestPersonas.FOUNTAIN_CITY_REGION_ID].name == "Fountain City"
            regions[TestPersonas.FOUNTAIN_CITY_REGION_ID].parentId == TestPersonas.EL_PASO_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.MANITOU_SPRINGS_CITY_REGION_ID)
            regions[TestPersonas.MANITOU_SPRINGS_CITY_REGION_ID].name == "Manitou Springs City"
            regions[TestPersonas.MANITOU_SPRINGS_CITY_REGION_ID].parentId == TestPersonas.EL_PASO_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.MONUMENT_TOWN_REGION_ID)
            regions[TestPersonas.MONUMENT_TOWN_REGION_ID].name == "Monument Town"
            regions[TestPersonas.MONUMENT_TOWN_REGION_ID].parentId == TestPersonas.EL_PASO_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.PALMER_LAKE_TOWN_REGION_ID)
            regions[TestPersonas.PALMER_LAKE_TOWN_REGION_ID].name == "Palmer Lake Town"
            regions[TestPersonas.PALMER_LAKE_TOWN_REGION_ID].parentId == TestPersonas.EL_PASO_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.CALHAN_TOWN_REGION_ID)
            regions[TestPersonas.CALHAN_TOWN_REGION_ID].name == "Calhan Town"
            regions[TestPersonas.CALHAN_TOWN_REGION_ID].parentId == TestPersonas.EL_PASO_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.GREEN_MOUNTAIN_FALLS_TOWN_REGION_ID)
            regions[TestPersonas.GREEN_MOUNTAIN_FALLS_TOWN_REGION_ID].name == "Green Mountain Falls Town"
            regions[TestPersonas.GREEN_MOUNTAIN_FALLS_TOWN_REGION_ID].parentId == TestPersonas.EL_PASO_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.RAMAH_TOWN_REGION_ID)
            regions[TestPersonas.RAMAH_TOWN_REGION_ID].name == "Ramah Town"
            regions[TestPersonas.RAMAH_TOWN_REGION_ID].parentId == TestPersonas.EL_PASO_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.ARAPAHOE_COUNTY_REGION_ID)
            regions[TestPersonas.ARAPAHOE_COUNTY_REGION_ID].name == "Arapahoe County Region"
            regions[TestPersonas.ARAPAHOE_COUNTY_REGION_ID].parentId == TestPersonas.COLORADO_STATE_REGION_ID

            regions.containsKey(TestPersonas.AURORA_CITY_REGION_ID)
            regions[TestPersonas.AURORA_CITY_REGION_ID].name == "Aurora City"
            regions[TestPersonas.AURORA_CITY_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.CENTENNIAL_CITY_REGION_ID)
            regions[TestPersonas.CENTENNIAL_CITY_REGION_ID].name == "Centennial City"
            regions[TestPersonas.CENTENNIAL_CITY_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.LITTLETON_CITY_REGION_ID)
            regions[TestPersonas.LITTLETON_CITY_REGION_ID].name == "Littleton City"
            regions[TestPersonas.LITTLETON_CITY_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.GREENWOOD_VILLAGE_CITY_REGION_ID)
            regions[TestPersonas.GREENWOOD_VILLAGE_CITY_REGION_ID].name == "Greenwood Village City"
            regions[TestPersonas.GREENWOOD_VILLAGE_CITY_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.ENGLEWOOD_CITY_REGION_ID)
            regions[TestPersonas.ENGLEWOOD_CITY_REGION_ID].name == "Englewood City"
            regions[TestPersonas.ENGLEWOOD_CITY_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.CHERRY_HILLS_VILLAGE_CITY_REGION_ID)
            regions[TestPersonas.CHERRY_HILLS_VILLAGE_CITY_REGION_ID].name == "Cherry Hills Village City"
            regions[TestPersonas.CHERRY_HILLS_VILLAGE_CITY_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.SHERIDAN_CITY_REGION_ID)
            regions[TestPersonas.SHERIDAN_CITY_REGION_ID].name == "Sheridan City"
            regions[TestPersonas.SHERIDAN_CITY_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.GLENDALE_CITY_REGION_ID)
            regions[TestPersonas.GLENDALE_CITY_REGION_ID].name == "Glendale City"
            regions[TestPersonas.GLENDALE_CITY_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.COLUMBINE_VALLEY_TOWN_REGION_ID)
            regions[TestPersonas.COLUMBINE_VALLEY_TOWN_REGION_ID].name == "Columbine Valley Town"
            regions[TestPersonas.COLUMBINE_VALLEY_TOWN_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.BOW_MAR_TOWN_REGION_ID)
            regions[TestPersonas.BOW_MAR_TOWN_REGION_ID].name == "Bow Mar Town"
            regions[TestPersonas.BOW_MAR_TOWN_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.FOXFIELD_TOWN_REGION_ID)
            regions[TestPersonas.FOXFIELD_TOWN_REGION_ID].name == "Foxfield Town"
            regions[TestPersonas.FOXFIELD_TOWN_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.DEER_TRAIL_TOWN_REGION_ID)
            regions[TestPersonas.DEER_TRAIL_TOWN_REGION_ID].name == "Deer Trail Town"
            regions[TestPersonas.DEER_TRAIL_TOWN_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.containsKey(TestPersonas.BENNETT_TOWN_REGION_ID)
            regions[TestPersonas.BENNETT_TOWN_REGION_ID].name == "Bennett Town"
            regions[TestPersonas.BENNETT_TOWN_REGION_ID].parentId == TestPersonas.ARAPAHOE_COUNTY_REGION_ID

            regions.size() == TestPersonas.SEEDED_REGION_IDS.size()
        }
    }

    def "seeded county boundaries accurately contain internal landmarks and exclude adjacent municipalities"() {
        when: "evaluating spatial containment for real-world locations against Denver County and El Paso County"
        String sql = """
            SELECT
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.9928 39.7392)')
                ) AS denver_city_hall_inside,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.6737 39.8561)')
                ) AS dia_inside,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-105.0814 39.7047)')
                ) AS lakewood_inside,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.8118 39.7083)')
                ) AS aurora_inside,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.8214 38.8339)')
                ) AS cos_city_hall_inside,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.6091 38.2544)')
                ) AS pueblo_inside,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.8214 38.8339)')
                ) AS cos_hall_inside_cos_city,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.7008 38.6822)')
                ) AS fountain_hall_inside_fountain,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.7008 38.6822)')
                ) AS fountain_hall_inside_cos_city,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.8093 39.7088)')
                ) AS arapahoe_covers_aurora_hall,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.8093 39.7088)')
                ) AS aurora_covers_aurora_hall,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.8093 39.7088)')
                ) AS denver_covers_aurora_hall,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.9942 39.7465)')
                ) AS denver_downtown_covers_16th_st_mall,
                ST_Covers(
                    (SELECT geom FROM administrative_regions WHERE id = ?),
                    ST_GeographyFromText('SRID=4326;POINT(-104.8214 38.8339)')
                ) AS cos_downtown_covers_cos_hall
        """
        List<UUID> params = [
                TestPersonas.DENVER_COUNTY_REGION_ID,
                TestPersonas.DENVER_COUNTY_REGION_ID,
                TestPersonas.DENVER_COUNTY_REGION_ID,
                TestPersonas.DENVER_COUNTY_REGION_ID,
                TestPersonas.EL_PASO_COUNTY_REGION_ID,
                TestPersonas.EL_PASO_COUNTY_REGION_ID,
                TestPersonas.COLORADO_SPRINGS_CITY_REGION_ID,
                TestPersonas.FOUNTAIN_CITY_REGION_ID,
                TestPersonas.COLORADO_SPRINGS_CITY_REGION_ID,
                TestPersonas.ARAPAHOE_COUNTY_REGION_ID,
                TestPersonas.AURORA_CITY_REGION_ID,
                TestPersonas.DENVER_COUNTY_REGION_ID,
                TestPersonas.DENVER_DOWNTOWN_REGION_ID,
                TestPersonas.COLORADO_SPRINGS_DOWNTOWN_REGION_ID
        ]

        Map<String, Object> spatialResults = executeQuery(sql, params as List<Object>).first()

        then: "landmarks, municipal boundaries, and downtown cores match expected containment"
        verifyAll(spatialResults) {
            denver_city_hall_inside == true
            dia_inside == true
            lakewood_inside == false
            aurora_inside == false
            cos_city_hall_inside == true
            pueblo_inside == false
            cos_hall_inside_cos_city == true
            fountain_hall_inside_fountain == true
            fountain_hall_inside_cos_city == false
            arapahoe_covers_aurora_hall == true
            aurora_covers_aurora_hall == true
            denver_covers_aurora_hall == false
            denver_downtown_covers_16th_st_mall == true
            cos_downtown_covers_cos_hall == true
        }
    }

    def "all seeded administrative regions are mathematically non-rectangular and topologically valid"() {
        when: "evaluating geometric validity and checking against bounding boxes"
        String regionIds = TestPersonas.SEEDED_REGION_IDS.collect { "'${it}'" }.join(',')
        String query = """
            SELECT
                id,
                name,
                ST_IsValid(geom::geometry) AS is_valid,
                ST_Equals(geom::geometry, ST_Envelope(geom::geometry)) AS is_axis_aligned_box,
                ST_Equals(geom::geometry, ST_OrientedEnvelope(geom::geometry)) AS is_oriented_box,
                ST_NPoints(geom::geometry) AS point_count
            FROM administrative_regions
            WHERE id IN (${regionIds})
            ORDER BY name
        """.toString()

        List<Map<String, Object>> rows = executeQuery(query)

        then: "all 28 seeded regions are retrieved and meet geometric validity constraints"
        verifyAll {
            rows.size() == TestPersonas.SEEDED_REGION_IDS.size()
            rows.every { it.is_valid == true }
            rows.every { ((it.point_count as Number).intValue()) >= 5 }
            rows.every { it.is_axis_aligned_box == false }
            rows.every { it.is_oriented_box == false }
        }
    }

    def "all seeded organizations in 3-tier organizational hierarchy exist with correct attributes"() {
        when: "querying organizations from the database"
        Map<UUID, Map<String, Object>> orgs = [:]
        organizationRepository.findAll().each { org ->
            orgs[org.id()] = [
                    name    : org.name(),
                    parentId: org.parentId(),
                    isPublic: org.isPublic(),
                    status  : org.verificationStatus().name()
            ]
        }

        then: "Level 1, Level 2, Level 3 organizations and standalone co-op exist with correct attributes"
        verifyAll {
            orgs.containsKey(TestPersonas.NATIONAL_RELIEF_NETWORK_ID)
            orgs[TestPersonas.NATIONAL_RELIEF_NETWORK_ID].name == "National Relief Network"
            orgs[TestPersonas.NATIONAL_RELIEF_NETWORK_ID].parentId == null
            orgs[TestPersonas.NATIONAL_RELIEF_NETWORK_ID].isPublic == true
            orgs[TestPersonas.NATIONAL_RELIEF_NETWORK_ID].status == "VERIFIED"

            orgs.containsKey(TestPersonas.COLORADO_STATE_CHAPTER_ID)
            orgs[TestPersonas.COLORADO_STATE_CHAPTER_ID].parentId == TestPersonas.NATIONAL_RELIEF_NETWORK_ID
            orgs[TestPersonas.COLORADO_STATE_CHAPTER_ID].status == "VERIFIED"

            orgs.containsKey(TestPersonas.UTAH_STATE_CHAPTER_ID)
            orgs[TestPersonas.UTAH_STATE_CHAPTER_ID].parentId == TestPersonas.NATIONAL_RELIEF_NETWORK_ID
            orgs[TestPersonas.UTAH_STATE_CHAPTER_ID].status == "VERIFIED"

            orgs.containsKey(TestPersonas.DENVER_METRO_BRANCH_ID)
            orgs[TestPersonas.DENVER_METRO_BRANCH_ID].parentId == TestPersonas.COLORADO_STATE_CHAPTER_ID
            orgs[TestPersonas.DENVER_METRO_BRANCH_ID].status == "VERIFIED"

            orgs.containsKey(TestPersonas.COLORADO_SPRINGS_BRANCH_ID)
            orgs[TestPersonas.COLORADO_SPRINGS_BRANCH_ID].parentId == TestPersonas.COLORADO_STATE_CHAPTER_ID
            orgs[TestPersonas.COLORADO_SPRINGS_BRANCH_ID].status == "VERIFIED"

            orgs.containsKey(TestPersonas.LOCAL_GRASSROOTS_COOP_ID)
            orgs[TestPersonas.LOCAL_GRASSROOTS_COOP_ID].parentId == null
            orgs[TestPersonas.LOCAL_GRASSROOTS_COOP_ID].status == "UNVERIFIED"
        }
    }

    def "region_users and organization_users bindings match expected persona assignments"() {
        when: "querying region_users bindings"
        Set<String> regionBindings = regionUserRepository.findAll()
                .collect { "${it.id().userId()}:${it.id().regionId()}:${it.role().name()}".toString() }
                .toSet()

        and: "querying organization_users bindings"
        Set<String> orgBindings = organizationUserRepository.findAll()
                .collect { "${it.id().userId()}:${it.id().organizationId()}:${it.role().name()}".toString() }
                .toSet()

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
        OpenIdClaims claims = [
                getEmail: { -> persona.email },
                get: { String k -> k == "email_verified" ? true : null }
        ] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "AuthentikAuthenticationMapper processes the login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication succeeds with user ID as name and full permission claims"
        response.isAuthenticated()
        def auth = response.getAuthentication().get()
        verifyAll(auth) {
            getName() == persona.id.toString()
            getAttributes().get("email") == persona.email
        }

        and: "all permissions mapped to the persona role are granted as claims"
        persona.systemRole.getPermissions().each { permission ->
            assert auth.getRoles().contains(permission.getClaim()):
                    "Missing claim ${permission.getClaim()} for persona ${persona.displayName}"
        }

        where:
        persona << TestPersonas.SEEDED_PERSONAS
    }

    def "JIT provisioning successfully creates jenny.niu@kaiju.local in PostgreSQL as STANDARD_USER"() {
        given: "an unseeded persona Jenny Niu who exists only in Authentik"
        def jenny = TestPersonas.JENNY_NIU
        assert !userRepository.findByEmail(jenny.email).isPresent(): "Jenny should not initially exist in DB"

        and: "an incoming token for Jenny"
        OpenIdClaims claims = [
                getEmail: { -> jenny.email },
                get: { String k -> k == "email_verified" ? true : null }
        ] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the Authentik mapper processes Jenny's initial login"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication succeeds and returns a valid session"
        response.isAuthenticated()
        def auth = response.getAuthentication().get()

        and: "Jenny was created in PostgreSQL as a STANDARD_USER"
        def dbUserOpt = userRepository.findByEmail(jenny.email)
        dbUserOpt.isPresent()
        def dbUser = dbUserOpt.get()
        verifyAll {
            auth.getAttributes().get("email") == jenny.email
            dbUser.email() == jenny.email
            dbUser.role() == UserRole.STANDARD_USER
            auth.getName() == dbUser.id().toString()
        }

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
        verifyAll(response) {
            !isAuthenticated()
            getMessage().get() == "No email present in OpenID claims"
        }
    }

    def "should reject authentication when email is blank in claims"() {
        given: "an incoming token with whitespace email"
        OpenIdClaims claims = [getEmail: { -> "   " }] as OpenIdClaims
        OpenIdTokenResponse token = new OpenIdTokenResponse()

        when: "the mapper processes the request"
        def publisher = mapper.createAuthenticationResponse("authentik", token, claims, null)
        AuthenticationResponse response = await(publisher)

        then: "authentication fails with an informative error message"
        verifyAll(response) {
            !isAuthenticated()
            getMessage().get() == "No email present in OpenID claims"
        }
    }
}
