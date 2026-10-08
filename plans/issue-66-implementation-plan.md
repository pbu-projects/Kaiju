# Technical Implementation Plan: Issue #66 — Release 0.0.6: Security Verification & Client Contract Handoff

## 1. Issue Overview and Scope

Release 0.0.6 establishes the security verification foundation, tenant isolation guarantees, concurrency race-condition hardening, offline spatial timezone resolution, and client contract stability for the Kaiju platform. This release transitions the platform from preliminary prototype CRUD functionality into an enterprise-grade, multi-tenant civic engagement engine ready for frontend and mobile client handoff.

### Audited Issues in Release 0.0.6

* **Issue #59: Testing: End-to-End IAM Token Integration & OIDC Claim Verification Tests**: Implement Spock integration tests with authentic RS256 token signing (via Nimbus JOSE/JWT) validated against an in-process WireMock JWKS endpoint. Verifies claim extraction, principal creation, database role synchronization, key rotation handling, and strict rejection (HTTP 401 Unauthorized) of expired, tampered, or rogue-signed tokens.
* **Issue #60: Testing: Automated 403 Forbidden & Privilege Containment Regression Suite across CRUD Controllers**: Comprehensive negative authorization matrix verifying every endpoint across all controllers. Enforces that anonymous callers receive HTTP 401 Unauthorized and authenticated callers lacking necessary roles or granular permissions receive HTTP 403 Forbidden.
* **Issue #61: Testing: Cross-Tenant Horizontal Isolation (IDOR) Integration Test Suite**: End-to-end integration assertions verifying that tenant boundaries are strictly impenetrable between Organization A and Organization B. Validates spatial boundary rejection rules, ensuring Regional Directors and Regional Agents cannot approve or mutate projects outside their assigned PostGIS polygons.
* **Issue #62: Testing: Concurrent Shift Capacity & Race Condition Verification Suite**: Multi-threaded concurrency verification simulating $N$ concurrent volunteers competing for $M$ available shift slots ($N > M$) using `ExecutorService` and `CompletableFuture`. Verifies database locking semantics, preventing overbooking and guaranteeing invariant capacity constraints under maximum load.
* **Issue #32: Spatial Timezone Resolution via PostGIS Boundary Intersections**: Introduce a `timezone_boundaries` PostGIS table with a GiST spatial index, Flyway migration, and spatial intersection query (`ST_Intersects`) in the `Location` creation and update lifecycle to assign canonical IANA timezones (e.g., `'America/Denver'`) without external third-party API calls.
* **OpenAPI 3.0 Client Contract Handoff**: Reflection-free ahead-of-time (AOT) OpenAPI 3.0 schema generation via `micronaut-openapi`, establishing frozen API specifications, DTO schemas, and contract verification test harnesses for mobile (`androidApp`, `iosApp`) and web frontend teams.

### Architectural Discipline and Standards

In alignment with core architectural principles and project guidelines:
1. **Reflection-Free AOT Execution**: All data transfer objects, request bodies, and entities utilize `@Serdeable` for compile-time Jackson serialization and deserialization. No runtime reflection or dynamic proxy generation is permitted. All Micronaut Data repositories must declare concrete mapped entity types.
2. **Zero Unnecessary Allocations**: High-frequency path operations (coordinate conversions, spatial queries, security claim evaluation) utilize primitives and stack-allocated records to maximize throughput.
3. **Strict Compile-Time Safety & Explicit Imports**: Fully qualified class names (FQCNs) in code bodies are forbidden; all imports are explicit. Fail-fast validation annotations (`@NotNull`, `@NotBlank`, `@Valid`) guard all controller and repository boundaries.
4. **Integration Testing Discipline**: All integration tests utilize Spock Framework with `@MicronautTest` and declarative HTTP interactions via `@Client("/") HttpClient` to validate real network serialization, filters, and security pipelines.
5. **Connection Pool Management**: High-concurrency integration tests must size database connection pools appropriately (`maximum-pool-size: 60`) to prevent connection pool exhaustion during multithreaded contention tests.

---

## 2. Architectural Design and Component Specifications

### 2.1 Mock OIDC and JWKS Test Fixture Architecture (Issue #59)

To decouple automated CI testing from external identity providers (such as Authentik or Keycloak), a dedicated in-process OIDC test harness is designed.

```
+-------------------------------------------------------------------+
|                     OidcMockServer (WireMock)                     |
|                                                                   |
|   /.well-known/openid-configuration                               |
|   /application/o/kaiju/jwks.json (Serves Public RSA JWKSet)       |
+---------------------------------+---------------------------------+
                                  ^
                                  | Fetch JWKS Public Key
                                  |
+---------------------------------+---------------------------------+
|               Micronaut Security JWT (RS256 Verifier)             |
|                                                                   |
|   1. Intercepts HTTP request with 'Authorization: Bearer <jwt>'   |
|   2. Fetches and caches JWKS from OidcMockServer                  |
|   3. Verifies RS256 signature and validates expiration (exp)     |
|   4. Delegates claims to AuthentikAuthenticationMapper            |
|   5. Resolves User from PostGIS DB & builds Security Context      |
+---------------------------------+---------------------------------+
                                  ^
                                  | Real HTTP Request with RS256 JWT
                                  |
+---------------------------------+---------------------------------+
|                       TestJwtSigner (Nimbus)                      |
|                                                                   |
|   - Holds Private RSA Key (2048-bit)                              |
|   - Mints Valid, Expired, Malformed, or Rogue-Signed JWTs         |
|   - Configurable claims: sub, email, groups, iss, aud, exp        |
+-------------------------------------------------------------------+
```

#### Shared Test Authentication Bean: `lol.pbu.kaiju.security.fixtures.TestAuthenticationFetcher`

To support automated negative testing across all test suites without isolated duplicate fetchers, a shared bean is introduced under test sources enabled for the `test` environment.

```java
package lol.pbu.kaiju.security.fixtures;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.AuthenticationFetcher;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.util.List;

@Requires(env = Environment.TEST)
@Singleton
public class TestAuthenticationFetcher implements AuthenticationFetcher<HttpRequest<?>> {

    @Override
    public Publisher<Authentication> fetchAuthentication(HttpRequest<?> request) {
        String testUser = request.getHeaders().get("X-Test-User");
        if (testUser != null && !testUser.isBlank()) {
            List<String> roles = request.getHeaders().getAll("X-Test-Role");
            return Publishers.just(Authentication.build(testUser, roles));
        }
        return Publishers.empty();
    }
}
```

#### Class Specification: `lol.pbu.kaiju.security.fixtures.OidcMockServer`

Manages the lifecycle of a WireMock HTTP server exposing standard OpenID Connect discovery and JWKS endpoints.

```java
package lol.pbu.kaiju.security.fixtures;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

public final class OidcMockServer implements AutoCloseable {

    private final WireMockServer wireMockServer;
    private final int port;
    private final String issuerUrl;

    public OidcMockServer(RSAKey rsaJwk) {
        this.wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        this.wireMockServer.start();
        this.port = wireMockServer.port();
        this.issuerUrl = "http://localhost:" + port + "/application/o/kaiju/";

        configureStubs(rsaJwk);
    }

    private void configureStubs(RSAKey rsaJwk) {
        String jwksJson = new JWKSet(rsaJwk.toPublicJWK()).toString();
        String openIdConfigJson = String.format("""
            {
              "issuer": "%s",
              "jwks_uri": "%sjwks.json",
              "authorization_endpoint": "%sauthorize",
              "token_endpoint": "%stoken",
              "userinfo_endpoint": "%suserinfo",
              "response_types_supported": ["code", "token", "id_token"]
            }
            """, issuerUrl, issuerUrl, issuerUrl, issuerUrl, issuerUrl);

        wireMockServer.stubFor(get(urlEqualTo("/application/o/kaiju/.well-known/openid-configuration"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(openIdConfigJson)));

        wireMockServer.stubFor(get(urlEqualTo("/application/o/kaiju/jwks.json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(jwksJson)));
    }

    public int getPort() {
        return port;
    }

    public String getIssuerUrl() {
        return issuerUrl;
    }

    public String getJwksUrl() {
        return issuerUrl + "jwks.json";
    }

    @Override
    public void close() {
        if (wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }
}
```

#### Class Specification: `lol.pbu.kaiju.security.fixtures.TestJwtSigner`

Generates cryptographically valid RSA keys and signs tokens for various test scenarios.

```java
package lol.pbu.kaiju.security.fixtures;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class TestJwtSigner {

    private final RSAKey rsaJwk;
    private final String issuer;
    private final String audience;

    public TestJwtSigner(String issuer, String audience) throws Exception {
        this.rsaJwk = new RSAKeyGenerator(2048)
                .keyID("kaiju-test-key-" + UUID.randomUUID())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        this.issuer = issuer;
        this.audience = audience;
    }

    public RSAKey getRsaJwk() {
        return rsaJwk;
    }

    public String createValidToken(String subject, String email, List<String> groups) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .claim("email", email)
                .claim("groups", groups)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minus(5, ChronoUnit.SECONDS)))
                .expirationTime(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .jwtID(UUID.randomUUID().toString())
                .build();

        return signClaims(claims, rsaJwk);
    }

    public String createExpiredToken(String subject, String email) throws Exception {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .claim("email", email)
                .issueTime(Date.from(past.minus(1, ChronoUnit.HOURS)))
                .expirationTime(Date.from(past))
                .jwtID(UUID.randomUUID().toString())
                .build();

        return signClaims(claims, rsaJwk);
    }

    public String createRogueSignedToken(String subject, String email) throws Exception {
        RSAKey rogueKey = new RSAKeyGenerator(2048)
                .keyID("rogue-attacker-key")
                .generate();

        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .claim("email", email)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .build();

        return signClaims(claims, rogueKey);
    }

    private String signClaims(JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(signingKey.getKeyID())
                        .build(),
                claims
        );
        signedJwt.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
        return signedJwt.serialize();
    }
}
```

#### User Controller Addition: `@Get("/me")` Endpoint

To provide a standard endpoint for authenticated callers to fetch their profile context (and support token verification tests):

```java
// Added to lol.pbu.kaiju.controller.UserController
@Get("/me")
public User getCurrentUser(Principal principal) {
    UUID userId = UUID.fromString(principal.getName());
    return userRepository.findById(userId)
            .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "User not found"));
}
```

---

### 2.2 Shift Capacity and Concurrency Control Architecture (Issue #62)

To satisfy Issue #62, the shift booking domain requires explicit capacity limits, relation-preserving pessimistic locking, and idempotency handling on re-registration.

```
       25 Concurrent Volunteer Requests (POST /shifts/{id}/signups)
                                 |
                                 v
+-------------------------------------------------------------------+
|                    ShiftController / ShiftService                 |
|                                                                   |
|   1. Begins Transaction (@Transactional)                          |
|   2. findByIdForUpdate(@NonNull UUID id)                          |
|      - @Lock(LockMode.PESSIMISTIC_WRITE)                          |
|      - @Join("project"), @Join("project.organization"),           |
|        @Join(value = "location", type = Join.Type.LEFT_FETCH)     |
|      -> Exclusive Row Lock on target shift with full relations    |
|                                                                   |
|   3. Checks Existing Registration:                                |
|      - If REGISTERED: Throw 409 Conflict                          |
|      - If CANCELLED: Re-activate to REGISTERED via UPDATE         |
|                                                                   |
|   4. Evaluates Invariant: activeSignups < shift.capacity()        |
|      - If TRUE:  INSERT INTO shift_signups ... (201 CREATED)     |
|      - If FALSE: Throw ShiftCapacityExceededException (409)       |
|                                                                   |
|   5. Releases Row Lock on Transaction Commit                      |
+---------------------------------+---------------------------------+
                                  |
            PostgreSQL Physical Table State (Zero Overbooking)
                                  v
+-------------------------------------------------------------------+
| shift_signups: Exactly M rows registered, N - M rejected with 409  |
+-------------------------------------------------------------------+
```

#### Domain Record: `lol.pbu.kaiju.domain.Shift` (Updated)

Add explicit `capacity` support to the existing entity.

```java
package lol.pbu.kaiju.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinTable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_MANY;
import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_ONE;

@Serdeable
@MappedEntity("shifts")
public record Shift(
        @Id
        @GeneratedValue
        UUID id,

        @Relation(MANY_TO_ONE)
        @NotNull(message = "Shift project is required.")
        Project project,

        boolean isVirtual,

        @Relation(MANY_TO_ONE)
        @Nullable
        Location location,

        @NotNull(message = "Shift start time is required.")
        OffsetDateTime startTime,

        @NotNull(message = "Shift end time is required.")
        OffsetDateTime endTime,

        @Nullable
        @Positive(message = "Shift capacity must be greater than zero if specified.")
        Integer capacity,

        @Relation(MANY_TO_MANY)
        @JoinTable(name = "shift_tags")
        List<Tag> tags
) {
    public Shift {
        if ((isVirtual && location != null) || (!isVirtual && location == null)) {
            throw new jakarta.validation.ValidationException("A shift must have a location if it is not virtual, and must not have a location if it is virtual.");
        }
    }

    @AssertTrue(message = "A shift must have a location if it is not virtual, and must not have a location if it is virtual.")
    public boolean isValidLocationLogic() {
        return (isVirtual && location == null) || (!isVirtual && location != null);
    }

    public Shift withId(@NotNull UUID newId) {
        return new Shift(
                newId,
                this.project(),
                this.isVirtual(),
                this.location(),
                this.startTime(),
                this.endTime(),
                this.capacity(),
                this.tags()
        );
    }
}
```

#### Domain Record: `lol.pbu.kaiju.domain.ShiftSignup`

Represents an individual volunteer's registration for a shift.

```java
package lol.pbu.kaiju.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.ShiftSignupStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

@Serdeable
@MappedEntity("shift_signups")
public record ShiftSignup(
        @Id
        @GeneratedValue
        UUID id,

        @NotNull(message = "Shift ID is required.")
        UUID shiftId,

        @NotNull(message = "User ID is required.")
        UUID userId,

        @NotNull(message = "Status is required.")
        ShiftSignupStatus status,

        @NotNull(message = "Created timestamp is required.")
        OffsetDateTime createdAt
) {
    public ShiftSignup withId(@NotNull UUID newId) {
        return new ShiftSignup(newId, this.shiftId(), this.userId(), this.status(), this.createdAt());
    }

    public ShiftSignup withStatus(@NotNull ShiftSignupStatus newStatus) {
        return new ShiftSignup(this.id(), this.shiftId(), this.userId(), newStatus, this.createdAt());
    }
}
```

#### Enum: `lol.pbu.kaiju.model.ShiftSignupStatus`

```java
package lol.pbu.kaiju.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum ShiftSignupStatus {
    REGISTERED,
    CANCELLED,
    ATTENDED,
    NO_SHOW
}
```

#### Repository: `lol.pbu.kaiju.repository.ShiftSignupRepository`

```java
package lol.pbu.kaiju.repository;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import lol.pbu.kaiju.domain.ShiftSignup;
import lol.pbu.kaiju.model.ShiftSignupStatus;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ShiftSignupRepository extends CrudRepository<ShiftSignup, UUID> {

    long countByShiftIdAndStatus(@NonNull UUID shiftId, @NonNull ShiftSignupStatus status);

    Optional<ShiftSignup> findByShiftIdAndUserId(@NonNull UUID shiftId, @NonNull UUID userId);

    List<ShiftSignup> findByShiftId(@NonNull UUID shiftId);
}
```

#### Repository Method in `lol.pbu.kaiju.repository.ShiftRepository`

Uses Micronaut Data's idiomatic `@Lock(LockMode.PESSIMISTIC_WRITE)` and explicit join fetches to preserve entity relation hydration while locking the shift row.

```java
// Added to lol.pbu.kaiju.repository.ShiftRepository
@NonNull
@Lock(LockMode.PESSIMISTIC_WRITE)
@Join(value = "project", type = Join.Type.FETCH)
@Join(value = "project.organization", type = Join.Type.FETCH)
@Join(value = "location", type = Join.Type.LEFT_FETCH)
Optional<Shift> findByIdForUpdate(@NonNull UUID id);
```

#### Service Specification: `lol.pbu.kaiju.service.ShiftService`

Guarantees atomic capacity verification and handles volunteer re-registration cleanly without duplicate key constraint violations.

```java
package lol.pbu.kaiju.service;

import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.Shift;
import lol.pbu.kaiju.domain.ShiftSignup;
import lol.pbu.kaiju.model.ShiftSignupStatus;
import lol.pbu.kaiju.repository.ShiftRepository;
import lol.pbu.kaiju.repository.ShiftSignupRepository;
import org.jspecify.annotations.NonNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.CONFLICT;
import static io.micronaut.http.HttpStatus.NOT_FOUND;

@Singleton
@ExecuteOn(TaskExecutors.BLOCKING)
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final ShiftSignupRepository shiftSignupRepository;

    public ShiftService(ShiftRepository shiftRepository, ShiftSignupRepository shiftSignupRepository) {
        this.shiftRepository = shiftRepository;
        this.shiftSignupRepository = shiftSignupRepository;
    }

    @Transactional
    public ShiftSignup registerVolunteer(@NonNull UUID shiftId, @NonNull UUID userId) {
        // 1. Acquire exclusive pessimistic row lock while hydrating shift entity graph
        Shift shift = shiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Shift not found"));

        // 2. Check existing registration state to prevent duplicate keys
        Optional<ShiftSignup> existingOpt = shiftSignupRepository.findByShiftIdAndUserId(shiftId, userId);
        if (existingOpt.isPresent()) {
            ShiftSignup existing = existingOpt.get();
            if (existing.status() == ShiftSignupStatus.REGISTERED) {
                throw new HttpStatusException(CONFLICT, "Volunteer is already registered for this shift");
            }
        }

        // 3. Evaluate capacity constraint under lock
        if (shift.capacity() != null) {
            long currentCount = shiftSignupRepository.countByShiftIdAndStatus(shiftId, ShiftSignupStatus.REGISTERED);
            if (currentCount >= shift.capacity()) {
                throw new HttpStatusException(CONFLICT, "Shift capacity has been reached");
            }
        }

        // 4. Update existing cancelled row, or save brand new signup
        if (existingOpt.isPresent()) {
            ShiftSignup reactivated = existingOpt.get().withStatus(ShiftSignupStatus.REGISTERED);
            return shiftSignupRepository.update(reactivated);
        } else {
            ShiftSignup newSignup = new ShiftSignup(
                    null,
                    shiftId,
                    userId,
                    ShiftSignupStatus.REGISTERED,
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
            return shiftSignupRepository.save(newSignup);
        }
    }
}
```

---

### 2.3 PostGIS Timezone Boundary Architecture (Issue #32)

Issue #32 requires offline resolution of IANA timezones when geographic coordinates are stored in the `locations` table.
Because `JtsPointConverter` binds JTS `Point` as PostgreSQL `PGobject` with type `'geography'`, `timezone_boundaries.geom` is defined as `GEOGRAPHY(MultiPolygon, 4326)` to preserve type symmetry and eliminate cast failures.

```
       Incoming Location Request (POST /locations with Lat/Lon Point)
                                 |
                                 v
+-------------------------------------------------------------------+
|                        LocationController                         |
|                                                                   |
|   1. Extracts Point geom (SRID 4326)                              |
|   2. Calls TimezoneResolutionService.resolveTimezone(point)       |
+---------------------------------+---------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|     PostGIS Spatial Query (ST_Intersects on GEOGRAPHY)            |
|                                                                   |
|   SELECT tb.tzid                                                  |
|   FROM timezone_boundaries tb                                     |
|   WHERE ST_Intersects(tb.geom, CAST(:pointGeom AS geography))     |
|   ORDER BY ST_Area(tb.geom) ASC                                   |
|   LIMIT 1;                                                        |
|                                                                   |
|   - Utilizes GiST index on timezone_boundaries(geom)             |
|   - Matches JtsPointConverter 'geography' PGobject binding       |
|   - ST_Area ASC resolves enclaves & localized sub-zones           |
|   - Fallback: 'UTC' or longitude-derived zone if offshore         |
+---------------------------------+---------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|   Saved Location: IANA timezone assigned (e.g., 'America/Denver') |
+-------------------------------------------------------------------+
```

#### Flyway Migration: `database/init/09-timezone-boundaries.sql`

```sql
-- 09-timezone-boundaries.sql: PostGIS Timezone Boundaries and Location Resolution

CREATE TABLE timezone_boundaries
(
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tzid VARCHAR(64)                       NOT NULL,
    geom GEOGRAPHY(MultiPolygon, 4326)     NOT NULL
);

-- Fast spatial indexing for polygon containment checks
CREATE INDEX idx_timezone_boundaries_geom ON timezone_boundaries USING GIST (geom);
CREATE INDEX idx_timezone_boundaries_tzid ON timezone_boundaries (tzid);

-- Alter locations table to persist resolved IANA timezone string
ALTER TABLE locations ADD COLUMN timezone VARCHAR(64);

-- Seed representative North American and reference global boundaries (Simplified WKT)
INSERT INTO timezone_boundaries (tzid, geom)
VALUES
('America/Denver', ST_Multi(ST_GeogFromText('POLYGON((-109.05 41.00, -102.05 41.00, -102.05 37.00, -109.05 37.00, -109.05 41.00))'))),
('America/Chicago', ST_Multi(ST_GeogFromText('POLYGON((-96.63 43.50, -87.52 43.50, -87.52 36.97, -96.63 36.97, -96.63 43.50))'))),
('America/New_York', ST_Multi(ST_GeogFromText('POLYGON((-79.76 45.00, -71.85 45.00, -71.85 40.49, -79.76 40.49, -79.76 45.00))'))),
('America/Los_Angeles', ST_Multi(ST_GeogFromText('POLYGON((-124.48 42.00, -114.13 42.00, -114.13 32.53, -124.48 32.53, -124.48 42.00))'))),
('America/Phoenix', ST_Multi(ST_GeogFromText('POLYGON((-114.81 37.00, -109.04 37.00, -109.04 31.33, -114.81 31.33, -114.81 37.00))'))),
('Europe/London', ST_Multi(ST_GeogFromText('POLYGON((-5.71 58.63, 1.76 58.63, 1.76 50.00, -5.71 50.00, -5.71 58.63))'))),
('UTC', ST_Multi(ST_GeogFromText('POLYGON((-180.00 85.00, 180.00 85.00, 180.00 -85.00, -180.00 -85.00, -180.00 85.00))')));
```

#### Domain Record: `lol.pbu.kaiju.domain.TimezoneBoundary`

Concrete entity mapping to ensure reflection-free AOT compilation in Micronaut Data.

```java
package lol.pbu.kaiju.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.JtsPolygonConverter;
import org.locationtech.jts.geom.Geometry;

import java.util.UUID;

import static io.micronaut.data.model.DataType.OBJECT;

@Serdeable
@MappedEntity("timezone_boundaries")
public record TimezoneBoundary(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "Timezone identifier is required.")
        @Size(min = 1, max = 64, message = "Timezone identifier must be between 1 and 64 characters.")
        String tzid,

        @NotNull(message = "Timezone geometry is required.")
        @TypeDef(type = OBJECT, converter = JtsPolygonConverter.class)
        Geometry geom
) {
    public TimezoneBoundary withId(@NotNull UUID newId) {
        return new TimezoneBoundary(newId, this.tzid(), this.geom());
    }
}
```

#### Updated Domain Record: `lol.pbu.kaiju.domain.Location`

```java
package lol.pbu.kaiju.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.JtsPointConverter;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

import static io.micronaut.data.model.DataType.OBJECT;

@Serdeable
@MappedEntity("locations")
public record Location(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "Location name is required.")
        @Size(min = 1, max = 255, message = "Location name must be between 1 and 255 characters.")
        String name,

        @NotBlank(message = "Location address line is required.")
        @Size(min = 1, max = 255, message = "Location address line must be between 1 and 255 characters.")
        String addressLine,

        @NotBlank(message = "Location city is required.")
        @Size(min = 1, max = 100, message = "Location city must be between 1 and 100 characters.")
        String city,

        @Nullable
        @Size(min = 1, max = 100, message = "Location state/province must be between 1 and 100 characters.")
        String stateProvince,

        @Nullable
        @Size(min = 1, max = 20, message = "Location postal code must be between 1 and 20 characters.")
        String postalCode,

        @NotBlank(message = "Location country code is required.")
        @Size(min = 2, max = 2, message = "Location country code must be 2 characters.")
        String countryCode,

        @TypeDef(type = OBJECT, converter = JtsPointConverter.class)
        Point geom,

        @Nullable
        @Size(min = 1, max = 64, message = "Timezone identifier must be between 1 and 64 characters.")
        String timezone
) {
    public Location withId(@NotNull UUID newId) {
        return new Location(
                newId,
                this.name(),
                this.addressLine(),
                this.city(),
                this.stateProvince(),
                this.postalCode(),
                this.countryCode(),
                this.geom(),
                this.timezone()
        );
    }

    public Location withTimezone(@Nullable String resolvedTimezone) {
        return new Location(
                this.id(),
                this.name(),
                this.addressLine(),
                this.city(),
                this.stateProvince(),
                this.postalCode(),
                this.countryCode(),
                this.geom(),
                resolvedTimezone
        );
    }
}
```

#### Repository Specification: `lol.pbu.kaiju.repository.TimezoneBoundaryRepository`

```java
package lol.pbu.kaiju.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import lol.pbu.kaiju.domain.TimezoneBoundary;
import org.jspecify.annotations.NonNull;
import org.locationtech.jts.geom.Point;

import java.util.Optional;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface TimezoneBoundaryRepository extends CrudRepository<TimezoneBoundary, UUID> {

    @Query(value = """
        SELECT tb.tzid 
        FROM timezone_boundaries tb 
        WHERE ST_Intersects(tb.geom, CAST(:pointGeom AS geography))
        ORDER BY ST_Area(tb.geom) ASC
        LIMIT 1
    """, nativeQuery = true)
    Optional<String> findTimezoneByPoint(@NonNull Point pointGeom);
}
```

#### Service Specification: `lol.pbu.kaiju.service.TimezoneResolutionService`

```java
package lol.pbu.kaiju.service;

import jakarta.inject.Singleton;
import lol.pbu.kaiju.repository.TimezoneBoundaryRepository;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Point;

import java.time.ZoneId;
import java.util.Optional;

@Singleton
public class TimezoneResolutionService {

    private final TimezoneBoundaryRepository timezoneRepository;

    public TimezoneResolutionService(TimezoneBoundaryRepository timezoneRepository) {
        this.timezoneRepository = timezoneRepository;
    }

    @NonNull
    public String resolveTimezone(@Nullable Point geom) {
        if (geom == null) {
            return "UTC";
        }
        Optional<String> resolved = timezoneRepository.findTimezoneByPoint(geom);
        if (resolved.isPresent() && isValidZoneId(resolved.get())) {
            return resolved.get();
        }
        return fallbackFromLongitude(geom.getX());
    }

    private boolean isValidZoneId(String zoneId) {
        try {
            ZoneId.of(zoneId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String fallbackFromLongitude(double longitude) {
        int rawOffset = (int) Math.round(longitude / 15.0);
        if (rawOffset == 0) {
            return "UTC";
        }
        return rawOffset > 0 ? "Etc/GMT-" + rawOffset : "Etc/GMT+" + Math.abs(rawOffset);
    }
}
```

---

### 2.4 Privilege Containment & Cross-Tenant Security Architecture (Issues #60 & #61)

#### Controller Hardening Matrix

| Controller / Route | Method | Security Annotation | Authorization Guard Rule |
| :--- | :--- | :--- | :--- |
| `/admin/users/{id}/role` | `PUT` | `@Secured("system:admin")` | Only `GLOBAL_ADMIN` may elevate or modify roles. |
| `/users` | `GET` | `@Secured("system:user:manage")` | System user directory listing restricted to admin operators. |
| `/users/me` | `GET` | `@Secured(IS_AUTHENTICATED)` | Resolves caller from security context principal. |
| `/users/{id}` | `PUT` | `@Secured(IS_AUTHENTICATED)` | Self-update only (`callerId == id`), or `GLOBAL_ADMIN`. |
| `/users/{id}` | `DELETE` | `@Secured("system:admin")` | Permanent deletion restricted to `GLOBAL_ADMIN`. |
| `/organizations` | `GET` | `@Secured("system:admin")` | Full directory browsing restricted to `GLOBAL_ADMIN`. |
| `/organizations/{id}` | `PUT` | `@Secured(IS_AUTHENTICATED)` | Must be `ORG_ADMIN` of target org or `GLOBAL_ADMIN`. |
| `/organizations/{id}` | `DELETE` | `@Secured(IS_AUTHENTICATED)` | Must be `ORG_ADMIN` of target org or `GLOBAL_ADMIN`. |
| `/organization-users` | `POST` | `@Secured(IS_AUTHENTICATED)` | Caller must be `ORG_ADMIN` for the target `organizationId`. |
| `/organization-users/...` | `PUT` | `@Secured(IS_AUTHENTICATED)` | Caller must be `ORG_ADMIN` for the target `organizationId`. |
| `/organization-users/...` | `DELETE` | `@Secured(IS_AUTHENTICATED)` | Caller must be `ORG_ADMIN` for the target `organizationId`. |
| `/administrative-regions` | `POST` | `@Secured("region:manage")` | Only `GLOBAL_ADMIN` or `REGION_DIRECTOR` may create regions. |
| `/administrative-regions/{id}` | `PUT` | `@Secured("region:manage")` | `REGION_DIRECTOR` within assigned region, or `GLOBAL_ADMIN`. |
| `/administrative-regions/{id}` | `DELETE`| `@Secured("system:admin")` | Region destruction restricted to `GLOBAL_ADMIN`. |
| `/boundaries` | `POST,PUT,DELETE` | `@Secured("region:manage")` | Boundary geometry creation/updates restricted to region admins. |
| `/projects` | `POST` | `@Secured(IS_AUTHENTICATED)` | `evaluateProjectCreationByUser`: PENDING if standard user, ACTIVE if verified Org Manager in region. |
| `/projects/{id}` | `PUT` | `@Secured(IS_AUTHENTICATED)` | `canModifyProject`: Org Manager of project org, or Regional Director within boundary. |
| `/projects/{id}` | `DELETE` | `@Secured(IS_AUTHENTICATED)` | `canModifyProject`: Org Manager of project org, or Regional Director within boundary. |
| `/projects/{id}/status` | `PUT` | `@Secured("project:approve")`| `authorizeRegionalAdminApproval`: Regional Director/Agent MUST intersect ALL project locations in PostGIS. |
| `/shifts` | `POST,PUT,DELETE` | `@Secured(IS_AUTHENTICATED)` | `verifyShiftAuthority`: Must be `ORG_MANAGER` of shift's project org or `GLOBAL_ADMIN`. |
| `/locations` | `POST` | `@Secured(IS_AUTHENTICATED)` | Authenticated users can create locations; automatically resolves IANA timezone. |
| `/locations/{id}` | `PUT,DELETE` | `@Secured(IS_AUTHENTICATED)` | Restricted to location creator or `GLOBAL_ADMIN`. |
| `/tags` | `POST,PUT,DELETE` | `@Secured("system:admin")` | Global tag ontology managed by administrators. |

---

### 2.5 OpenAPI 3.0 Schema Generation and Client Contract Architecture

To enable reflection-free, compile-time OpenAPI 3.0 generation:

#### 1. Gradle Build Configuration (`build.gradle.kts`)

```kotlin
dependencies {
    annotationProcessor("io.micronaut.openapi:micronaut-openapi")
    compileOnly("io.swagger.core.v3:swagger-annotations")
}
```

#### 2. Application Definition Annotations (`lol.pbu.Application`)

```java
package lol.pbu;

import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;

@OpenAPIDefinition(
        info = @Info(
                title = "Kaiju Civic Engine API",
                version = "0.0.6",
                description = "High-performance spatial volunteer management and civic engagement engine",
                contact = @Contact(name = "Peanut Butter Unicorn", url = "https://kaiju.pbu.lol")
        ),
        servers = @Server(url = "/", description = "Current Server"),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class Application {
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
```

#### 3. Client Contract Freeze and Export Task

A Gradle task exports the compile-time generated OpenAPI document from `build/classes/java/main/META-INF/swagger/` into `docs/openapi.yaml` and executes schema verification to ensure client SDK compatibility.

---

## 3. Step-by-Step Implementation Strategy

```
+-------------------------------------------------------------------------------+
| PHASE 1: PostGIS Timezone Resolution & Migration (#32)                        |
| - Create database/init/09-timezone-boundaries.sql (GEOGRAPHY type)            |
| - Implement TimezoneBoundary domain record (@MappedEntity)                    |
| - Update Location record with timezone field                                  |
| - Implement TimezoneBoundaryRepository & TimezoneResolutionService            |
| - Wire TimezoneResolutionService into LocationController                      |
+---------------------------------------+---------------------------------------+
                                        |
                                        v
+-------------------------------------------------------------------------------+
| PHASE 2: Shift Capacity Domain & Concurrency Engine (#62)                     |
| - Update Shift record with capacity validation                                |
| - Implement ShiftSignup record (withStatus helper) & ShiftSignupRepository    |
| - Implement ShiftRepository.findByIdForUpdate() (@Lock + relation joins)      |
| - Implement ShiftService.registerVolunteer() with pessimistic locking         |
|   and CANCELLED reactivation update                                           |
| - Expose POST /shifts/{id}/signups endpoint in ShiftController                |
+---------------------------------------+---------------------------------------+
                                        |
                                        v
+-------------------------------------------------------------------------------+
| PHASE 3: Controller Security Hardening & Authorization Layer (#60, #61)       |
| - Add verifyOrgAdminAuthority to OrganizationController (PUT, DELETE)        |
| - Secure AdministrativeRegionController, BoundaryController, TagController   |
| - Add GET /users/me endpoint to UserController                                |
| - Implement shared TestAuthenticationFetcher (@Requires(env = "test"))        |
+---------------------------------------+---------------------------------------+
                                        |
                                        v
+-------------------------------------------------------------------------------+
| PHASE 4: Mock OIDC & WireMock/Nimbus JWKS Test Fixture (#59)                  |
| - Implement OidcMockServer and TestJwtSigner test fixtures                    |
| - Configure Spock environment with dynamic JWKS URL injection                |
| - Build OidcTokenVerificationSpec for RS256 token verification                |
+---------------------------------------+---------------------------------------+
                                        |
                                        v
+-------------------------------------------------------------------------------+
| PHASE 5: OpenAPI 3.0 Spec Generation & Contract Verification                  |
| - Add swagger-annotations and micronaut-openapi to build.gradle.kts           |
| - Annotate Application and Controllers with OpenAPI annotations               |
| - Implement ClientContractVerificationSpec checking generated OpenAPI spec    |
+---------------------------------------+---------------------------------------+
                                        |
                                        v
+-------------------------------------------------------------------------------+
| PHASE 6: Full Integration Test Suite Execution & Verification                 |
| - Configure test pool size (maximum-pool-size: 60) for concurrency suite     |
| - Run ./gradlew test (All Specs)                                              |
| - Run ./gradlew lighthouse (Markdown audit)                                   |
| - Verify JaCoCo coverage >= 85%                                               |
+-------------------------------------------------------------------------------+
```

### Phase 1: PostGIS Timezone Resolution and Migration (Issue #32)

1. **Flyway Migration Creation**: Add `database/init/09-timezone-boundaries.sql` containing `timezone_boundaries` table with `GEOGRAPHY(MultiPolygon, 4326)` column, GiST index, and `timezone` column on `locations`.
2. **Domain Models**: Create `TimezoneBoundary.java` record with `@MappedEntity("timezone_boundaries")`. Update `Location.java` with `timezone` field.
3. **Repository & Service**: Implement `TimezoneBoundaryRepository` with `ST_Intersects(tb.geom, CAST(:pointGeom AS geography))` native query. Implement `TimezoneResolutionService` with longitude fallback.
4. **Controller Integration**: Update `LocationController.addLocation` and `updateLocation` to resolve and assign timezone before persistence.

### Phase 2: Shift Capacity Domain & Concurrency Engine (Issue #62)

1. **Shift Model Update**: Add `@Nullable @Positive Integer capacity` to `Shift.java`.
2. **Shift Signup Entity**: Implement `ShiftSignup.java` (with `withStatus` method) and `ShiftSignupRepository.java`.
3. **Pessimistic Locking**: Add `findByIdForUpdate` to `ShiftRepository.java` using `@Lock(LockMode.PESSIMISTIC_WRITE)` and explicit `@Join` annotations.
4. **Service & Controller**: Implement `ShiftService.registerVolunteer` wrapping pessimistic capacity checks and re-registration handling in `@Transactional`. Add `POST /shifts/{id}/signups` to `ShiftController.java`.

### Phase 3: Controller Security Hardening & Authorization Layer (Issues #60 & #61)

1. **Organization Controller Hardening**: Add caller verification to `updateOrganization` and `deleteOrganization` in `OrganizationController.java`.
2. **Region & Boundary Hardening**: Restrict mutation endpoints on `AdministrativeRegionController` and `BoundaryController` to `region:manage` or `system:admin`.
3. **User Controller Addition**: Add `GET /users/me` resolving the authenticated caller from the security context principal.
4. **Shared Test Authentication Fetcher**: Implement `TestAuthenticationFetcher` in test sources under `lol.pbu.kaiju.security.fixtures`.

### Phase 4: Mock OIDC & WireMock/Nimbus JWKS Test Fixture (Issue #59)

1. **Fixture Construction**: Create `OidcMockServer` (WireMock) and `TestJwtSigner` (Nimbus JOSE/JWT).
2. **Dynamic Configuration**: Configure Micronaut Security to read JWKS from dynamic WireMock port in test specifications.
3. **Test Suite**: Construct `OidcTokenVerificationSpec.groovy` verifying claim mapping, signature verification, and expired token rejection.

### Phase 5: OpenAPI 3.0 Spec Generation & Contract Verification

1. **Build Configuration**: Add `micronaut-openapi` processor and `swagger-annotations` to `build.gradle.kts`.
2. **Annotation**: Add `@OpenAPIDefinition` to `Application.java` and annotate CRUD controllers.
3. **Contract Test**: Implement `ClientContractVerificationSpec.groovy` validating generated OpenAPI YAML.

### Phase 6: Full Integration Test Execution & Verification

1. Execute `./gradlew test` with test connection pool sized to 60 connections.
2. Execute `./gradlew lighthouse` to confirm link integrity and markdown standards.
3. Verify JaCoCo coverage >= 85% on security and domain packages.

---

## 4. Comprehensive Testing and Verification Plan

### 4.1 IAM Token & OIDC Claim Verification Suite (`OidcTokenVerificationSpec.groovy`)

Tests real RS256 token verification against in-process MockWebServer/WireMock JWKS endpoint using `@Client("/") HttpClient`.

```groovy
package lol.pbu.kaiju.security

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.security.fixtures.OidcMockServer
import lol.pbu.kaiju.security.fixtures.TestJwtSigner
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

@MicronautTest(transactional = false)
@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.token.jwt.enabled", value = "true")
class OidcTokenVerificationSpec extends Specification implements TestPropertyProvider {

    @Shared
    @AutoCleanup
    static OidcMockServer mockServer

    @Shared
    static TestJwtSigner jwtSigner

    @Inject
    @Client("/")
    HttpClient httpClient

    @Inject
    UserRepository userRepository

    BlockingHttpClient getClient() {
        httpClient.toBlocking()
    }

    @Override
    Map<String, String> getProperties() {
        jwtSigner = new TestJwtSigner("http://localhost:placeholder/application/o/kaiju/", "kaiju-client")
        mockServer = new OidcMockServer(jwtSigner.getRsaJwk())
        jwtSigner = new TestJwtSigner(mockServer.getIssuerUrl(), "kaiju-client")

        return [
                "micronaut.security.token.jwt.signatures.jwks.authentik.url": mockServer.getJwksUrl(),
                "micronaut.security.oauth2.clients.authentik.openid.issuer": mockServer.getIssuerUrl()
        ]
    }

    def "OIDC | should successfully authenticate caller with valid RS256 JWT and map claims"() {
        given: "a valid token for an existing user in the database"
        String email = "verified-${UUID.randomUUID()}@example.com"
        User dbUser = userRepository.save(new User(null, email, UserRole.GLOBAL_ADMIN, java.time.OffsetDateTime.now()))
        String token = jwtSigner.createValidToken(dbUser.id().toString(), email, ["kaiju-admins"])

        when: "calling /users/me with Bearer authorization"
        def response = client.exchange(
                HttpRequest.GET("/users/me")
                        .bearerAuth(token)
        )

        then: "request succeeds with 200 OK and principal identity is resolved"
        response.status == HttpStatus.OK
        response.body().contains(email)
    }

    def "OIDC | should reject expired RS256 token with 401 UNAUTHORIZED"() {
        given: "an expired token"
        String token = jwtSigner.createExpiredToken(UUID.randomUUID().toString(), "expired@example.com")

        when: "calling secured endpoint"
        client.exchange(HttpRequest.GET("/users/me").bearerAuth(token))

        then: "401 Unauthorized is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "OIDC | should reject token signed with untrusted rogue RSA key with 401 UNAUTHORIZED"() {
        given: "a token signed with a key not present in the JWKS"
        String token = jwtSigner.createRogueSignedToken(UUID.randomUUID().toString(), "rogue@example.com")

        when: "calling secured endpoint"
        client.exchange(HttpRequest.GET("/users/me").bearerAuth(token))

        then: "401 Unauthorized is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "OIDC | should reject tampered token payload with 401 UNAUTHORIZED"() {
        given: "a valid token with a tampered body"
        String token = jwtSigner.createValidToken(UUID.randomUUID().toString(), "tamper@example.com", [])
        String[] parts = token.split("\\.")
        String tamperedToken = parts[0] + "." + parts[1] + "tampered" + "." + parts[2]

        when: "calling secured endpoint"
        client.exchange(HttpRequest.GET("/users/me").bearerAuth(tamperedToken))

        then: "401 Unauthorized is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }
}
```

---

### 4.2 Automated 403 Forbidden Regression Suite (`PrivilegeContainmentSecurityMatrixSpec.groovy`)

Exhaustive matrix validating every route against Anonymous, Standard User, Org Manager, Regional Agent, Regional Director, and Global Admin callers.

```groovy
package lol.pbu.kaiju.security

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import spock.lang.Unroll

@Property(name = "micronaut.security.enabled", value = "true")
@MicronautTest(transactional = false)
class PrivilegeContainmentSecurityMatrixSpec extends BaseControllerSpec {

    @Inject
    @Client("/")
    HttpClient httpClient

    BlockingHttpClient getClient() {
        httpClient.toBlocking()
    }

    @Unroll
    def "Containment | Route [#method #path] as [#role] should yield [#expectedStatus]"(
            String method, String path, String role, HttpStatus expectedStatus
    ) {
        given: "an HTTP request configured for the target role"
        def request = buildRequest(method, path, role)

        when: "the endpoint is invoked"
        def responseStatus
        try {
            def res = client.exchange(request)
            responseStatus = res.status
        } catch (HttpClientResponseException e) {
            responseStatus = e.status
        }

        then: "the response status exactly matches the expected security containment rule"
        responseStatus == expectedStatus

        where:
        method   | path                                | role             | expectedStatus
        // Admin user management
        "PUT"    | "/admin/users/${UUID.randomUUID()}/role"  | "ANONYMOUS"     | HttpStatus.UNAUTHORIZED
        "PUT"    | "/admin/users/${UUID.randomUUID()}/role"  | "STANDARD_USER" | HttpStatus.FORBIDDEN
        "PUT"    | "/admin/users/${UUID.randomUUID()}/role"  | "REGION_AGENT"  | HttpStatus.FORBIDDEN
        "PUT"    | "/admin/users/${UUID.randomUUID()}/role"  | "GLOBAL_ADMIN"  | HttpStatus.OK

        // Bulk organization listing
        "GET"    | "/organizations"                    | "ANONYMOUS"     | HttpStatus.UNAUTHORIZED
        "GET"    | "/organizations"                    | "STANDARD_USER" | HttpStatus.FORBIDDEN
        "GET"    | "/organizations"                    | "GLOBAL_ADMIN"  | HttpStatus.OK

        // Administrative region mutation
        "POST"   | "/administrative-regions"           | "ANONYMOUS"     | HttpStatus.UNAUTHORIZED
        "POST"   | "/administrative-regions"           | "STANDARD_USER" | HttpStatus.FORBIDDEN
        "POST"   | "/administrative-regions"           | "REGION_AGENT"  | HttpStatus.FORBIDDEN
        "POST"   | "/administrative-regions"           | "GLOBAL_ADMIN"  | HttpStatus.OK

        // Tag management
        "POST"   | "/tags"                             | "ANONYMOUS"     | HttpStatus.UNAUTHORIZED
        "POST"   | "/tags"                             | "STANDARD_USER" | HttpStatus.FORBIDDEN
        "POST"   | "/tags"                             | "GLOBAL_ADMIN"  | HttpStatus.OK
    }

    private HttpRequest<?> buildRequest(String method, String path, String role) {
        HttpRequest<?> req
        switch (method) {
            case "GET": req = HttpRequest.GET(path); break
            case "POST": req = HttpRequest.POST(path, "{}"); break
            case "PUT": req = HttpRequest.PUT(path, "{}"); break
            case "DELETE": req = HttpRequest.DELETE(path); break
            default: throw new IllegalArgumentException("Unsupported method: " + method)
        }
        if (role != "ANONYMOUS") {
            req.header("X-Test-User", "test-" + role.toLowerCase())
            req.header("X-Test-Role", roleToClaim(role))
        }
        return req
    }

    private String roleToClaim(String role) {
        switch (role) {
            case "GLOBAL_ADMIN": return "system:admin"
            case "REGION_DIRECTOR": return "region:manage"
            case "REGION_AGENT": return "project:approve"
            case "STANDARD_USER": return "project:create"
            default: return "none"
        }
    }
}
```

---

### 4.3 Multi-Tenant Cross-Org & Spatial Boundary Isolation Suite (`CrossTenantIsolationSpec.groovy`)

Verifies that Org A admins cannot mutate Org B entities and that regional administrators cannot approve or mutate projects outside their assigned PostGIS polygon.

```groovy
package lol.pbu.kaiju.security

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.VerificationStatus
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

@Property(name = "micronaut.security.enabled", value = "true")
@MicronautTest(transactional = false)
class CrossTenantIsolationSpec extends BaseControllerSpec {

    @Inject
    @Client("/")
    HttpClient httpClient

    @Inject
    GeometryFactory geometryFactory

    BlockingHttpClient getClient() {
        httpClient.toBlocking()
    }

    def "Cross-Tenant IDOR | Org A admin cannot mutate Org B profile"() {
        given: "Org A (Admin: Alice) and Org B (Admin: Bob)"
        UUID aliceId = UUID.randomUUID()
        UUID orgAId = UUID.randomUUID()
        UUID orgBId = UUID.randomUUID()

        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, 'alice@example.com', 'STANDARD_USER')", aliceId)
        executeUpdate("INSERT INTO organizations (id, name, verification_status) VALUES (?, 'Org A', 'VERIFIED')", orgAId)
        executeUpdate("INSERT INTO organizations (id, name, verification_status) VALUES (?, 'Org B', 'VERIFIED')", orgBId)
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_ADMIN')", aliceId, orgAId)

        when: "Alice attempts to update Org B"
        def payload = new Organization(orgBId, "Org B Malicious Rename", null, null, true, VerificationStatus.VERIFIED, null, [])
        client.exchange(
                HttpRequest.PUT("/organizations/${orgBId}", payload)
                        .header("X-Test-User", aliceId.toString())
                        .header("X-Test-Role", "project:create")
        )

        then: "403 Forbidden is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "Spatial Isolation | Regional Director cannot approve project outside assigned PostGIS polygon"() {
        given: "Denver boundary assigned to Director Dave"
        UUID daveId = UUID.randomUUID()
        UUID denverRegionId = UUID.randomUUID()
        String denverWkt = "POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))"
        
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Denver', ST_GeogFromText(?))", denverRegionId, denverWkt)
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, 'dave@example.com', 'REGION_DIRECTOR')", daveId)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", daveId, denverRegionId)

        and: "a PENDING project physically located in Boulder (outside Denver)"
        UUID orgId = UUID.randomUUID()
        UUID locId = UUID.randomUUID()
        UUID projectId = UUID.randomUUID()
        String boulderPointWkt = "POINT(-105.2705 40.0150)"

        executeUpdate("INSERT INTO organizations (id, name, verification_status) VALUES (?, 'Org Boulder', 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Boulder Site', '1st St', 'Boulder', 'US', ST_GeographyFromText(?))", locId, boulderPointWkt)
        executeUpdate("INSERT INTO projects (id, organization_id, title, description, project_type, status) VALUES (?, ?, 'Boulder Initiative', 'Desc', 'STANDARD', 'PENDING')", projectId, orgId)
        executeUpdate("INSERT INTO project_locations (project_id, location_id) VALUES (?, ?)", projectId, locId)

        when: "Dave attempts to approve the Boulder project"
        client.exchange(
                HttpRequest.PUT("/projects/${projectId}/status", "{}")
                        .header("X-Test-User", daveId.toString())
                        .header("X-Test-Role", "project:approve")
        )

        then: "403 Forbidden is returned due to geographic boundary mismatch"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }
}
```

---

### 4.4 High-Load Shift Capacity Race Condition Verification Suite (`ConcurrentShiftCapacitySpec.groovy`)

Multi-threaded concurrency verification simulating $N = 25$ simultaneous volunteers contending for $M = 5$ slots with connection pool capacity explicitly sized to 60 connections. Also validates that volunteers with cancelled signups can re-register cleanly without database errors.

```groovy
package lol.pbu.kaiju.concurrency

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.repository.ShiftRepository
import spock.lang.Specification

import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "datasources.default.maximum-pool-size", value = "60")
@Property(name = "datasources.default.connection-timeout", value = "10000")
@MicronautTest(transactional = false)
class ConcurrentShiftCapacitySpec extends BaseControllerSpec {

    @Inject
    @Client("/")
    HttpClient httpClient

    @Inject
    ShiftRepository shiftRepository

    BlockingHttpClient getClient() {
        httpClient.toBlocking()
    }

    def "Concurrency | Exactly M volunteers are booked when N volunteers compete simultaneously (N > M)"() {
        given: "a shift with capacity limit M = 5"
        int capacityM = 5
        int volunteersN = 25

        UUID orgId = UUID.randomUUID()
        UUID projectId = UUID.randomUUID()
        UUID shiftId = UUID.randomUUID()

        executeUpdate("INSERT INTO organizations (id, name, verification_status) VALUES (?, 'Civic Care', 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO projects (id, organization_id, title, description, project_type, status) VALUES (?, ?, 'Park Cleanup', 'Clean', 'STANDARD', 'ACTIVE')", projectId, orgId)
        executeUpdate("""
            INSERT INTO shifts (id, project_id, is_virtual, start_time, end_time, capacity) 
            VALUES (?, ?, TRUE, NOW(), NOW() + INTERVAL '2 HOURS', ?)
        """, shiftId, projectId, capacityM)

        and: "N distinct volunteer identities seeded in the database"
        List<UUID> volunteerIds = (1..volunteersN).collect {
            UUID vId = UUID.randomUUID()
            executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", vId, "volunteer-${vId}@example.com")
            return vId
        }

        and: "a concurrency harness with synchronized release gate"
        def executor = Executors.newFixedThreadPool(volunteersN)
        def readyLatch = new CountDownLatch(volunteersN)
        def startLatch = new CountDownLatch(1)
        List<CompletableFuture<Integer>> futures = []

        when: "all N volunteers fire simultaneous registration requests"
        volunteerIds.each { vId ->
            futures << CompletableFuture.supplyAsync({ ->
                readyLatch.countDown()
                startLatch.await() // Block until all N threads are ready
                try {
                    def res = client.exchange(
                            HttpRequest.POST("/shifts/${shiftId}/signups", "{}")
                                    .header("X-Test-User", vId.toString())
                                    .header("X-Test-Role", "project:create")
                    )
                    return res.status.code
                } catch (HttpClientResponseException e) {
                    return e.status.code
                }
            }, executor)
        }

        readyLatch.await() // Wait for all threads to align at starting gate
        startLatch.countDown() // Release the concurrent requests simultaneously

        List<Integer> statuses = futures.collect { it.join() }

        then: "exactly M requests succeed with 200/201 and N - M fail with 409 Conflict"
        int successfulSignups = statuses.count { it == 200 || it == 201 }
        int rejectedSignups = statuses.count { it == 409 }

        successfulSignups == capacityM
        rejectedSignups == (volunteersN - capacityM)

        and: "the database contains strictly M registered rows (Zero Overbooking)"
        def countRow = sql.firstRow("SELECT COUNT(*) AS total FROM shift_signups WHERE shift_id = ? AND status = 'REGISTERED'", [shiftId])
        countRow.total == capacityM

        cleanup:
        executor.shutdown()
    }

    def "Concurrency | Volunteer with CANCELLED signup can re-register without duplicate key error"() {
        given: "a shift with capacity and a volunteer with CANCELLED signup"
        UUID orgId = UUID.randomUUID()
        UUID projectId = UUID.randomUUID()
        UUID shiftId = UUID.randomUUID()
        UUID volunteerId = UUID.randomUUID()

        executeUpdate("INSERT INTO organizations (id, name, verification_status) VALUES (?, 'Civic Care ReReg', 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO projects (id, organization_id, title, description, project_type, status) VALUES (?, ?, 'Food Bank', 'Bank', 'STANDARD', 'ACTIVE')", projectId, orgId)
        executeUpdate("INSERT INTO shifts (id, project_id, is_virtual, start_time, end_time, capacity) VALUES (?, ?, TRUE, NOW(), NOW() + INTERVAL '2 HOURS', 10)", shiftId, projectId)
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", volunteerId, "re-register@example.com")
        executeUpdate("INSERT INTO shift_signups (shift_id, user_id, status) VALUES (?, ?, 'CANCELLED')", shiftId, volunteerId)

        when: "the volunteer registers again"
        def response = client.exchange(
                HttpRequest.POST("/shifts/${shiftId}/signups", "{}")
                        .header("X-Test-User", volunteerId.toString())
                        .header("X-Test-Role", "project:create")
        )

        then: "registration succeeds and status is reactivated to REGISTERED"
        response.status == HttpStatus.CREATED || response.status == HttpStatus.OK
        def row = sql.firstRow("SELECT status FROM shift_signups WHERE shift_id = ? AND user_id = ?", [shiftId, volunteerId])
        row.status == "REGISTERED"
    }
}
```

---

### 4.5 PostGIS Timezone Spatial Resolution Suite (`TimezoneSpatialResolutionSpec.groovy`)

```groovy
package lol.pbu.kaiju.spatial

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.repository.LocationRepository
import lol.pbu.kaiju.service.TimezoneResolutionService
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import spock.lang.Unroll

@MicronautTest(transactional = false)
class TimezoneSpatialResolutionSpec extends BaseControllerSpec {

    @Inject
    TimezoneResolutionService timezoneService

    @Inject
    LocationRepository locationRepository

    @Inject
    GeometryFactory geometryFactory

    @Unroll
    def "Timezone | Coordinate Point [#lon, #lat] should resolve to IANA timezone [#expectedTimezone]"(
            double lon, double lat, String expectedTimezone
    ) {
        given: "a geographic coordinate point"
        Point point = geometryFactory.createPoint(new Coordinate(lon, lat))

        when: "the timezone is resolved via PostGIS intersection"
        String resolvedTz = timezoneService.resolveTimezone(point)

        then: "it exactly matches the expected IANA timezone identifier"
        resolvedTz == expectedTimezone

        where:
        lon       | lat     | expectedTimezone
        -104.9903 | 39.7392 | "America/Denver"      // Downtown Denver, CO
        -87.6298  | 41.8781 | "America/Chicago"     // Chicago, IL
        -74.0060  | 40.7128 | "America/New_York"    // New York, NY
        -118.2437 | 34.0522 | "America/Los_Angeles" // Los Angeles, CA
        -112.0740 | 33.4484 | "America/Phoenix"     // Phoenix, AZ
        -0.1276   | 51.5074 | "Europe/London"       // London, UK
        0.0000    | -80.000 | "UTC"                 // Antarctica / Offshore fallback
    }
}
```

---

### 4.6 OpenAPI Schema & Client Contract Validation Suite (`ClientContractVerificationSpec.groovy`)

```groovy
package lol.pbu.kaiju.contract

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import org.yaml.snakeyaml.Yaml
import spock.lang.Specification

@MicronautTest
class ClientContractVerificationSpec extends Specification {

    def "OpenAPI | Generated AOT spec exists and validates against OpenAPI 3.0 schema contract"() {
        given: "the compiled OpenAPI YAML specification"
        File specFile = new File("build/classes/java/main/META-INF/swagger/kaiju-0.0.6.yml")
        if (!specFile.exists()) {
            specFile = new File("docs/openapi.yaml")
        }

        expect: "the spec file is present on the filesystem"
        specFile.exists()

        when: "the spec is parsed as structured YAML"
        Map<String, Object> spec = new Yaml().load(specFile.text)

        then: "metadata and core endpoints are strictly declared"
        spec.openapi.startsWith("3.0")
        spec.info.title == "Kaiju Civic Engine API"
        spec.info.version == "0.0.6"

        and: "critical client routes are present in the contract"
        Map paths = spec.paths as Map
        paths.containsKey("/projects")
        paths.containsKey("/projects/search-by-location")
        paths.containsKey("/shifts")
        paths.containsKey("/shifts/{id}/signups")
        paths.containsKey("/locations")
        paths.containsKey("/organizations")
        paths.containsKey("/users/me")

        and: "Shift model declares capacity property"
        Map components = spec.components as Map
        Map schemas = components.schemas as Map
        Map shiftSchema = schemas["Shift"] as Map
        shiftSchema.properties.containsKey("capacity")
    }
}
```

---

## 5. Risk Analysis and Mitigations

| Risk Scenario | Severity | Impact | Architectural Mitigation |
| :--- | :--- | :--- | :--- |
| **Database Connection Pool Exhaustion** | High | 25-50 concurrent volunteer threads can exhaust default 10-connection pool. | Explicitly configure `datasources.default.maximum-pool-size: 60` and `connection-timeout: 10000` in test profile properties. |
| **Shift Relation Deserialization Loss** | High | Raw SQL in repository queries strips joined relations like `project` and `location`. | Use idiomatic Micronaut Data `@Lock(LockMode.PESSIMISTIC_WRITE)` with `@Join` annotations instead of unmapped native queries. |
| **Duplicate Key Constraint Violations on Re-registration** | Medium | A volunteer who previously cancelled a signup cannot register again if the code only attempts `INSERT`. | `ShiftService.registerVolunteer` detects existing `CANCELLED` rows and executes an `UPDATE` reactivating status to `REGISTERED`. |
| **Geometry vs Geography Type Mismatches** | Medium | PostGIS runtime failure if comparing Geometry with Geography without matching SRID and spatial type. | Define `timezone_boundaries.geom` as `GEOGRAPHY(MultiPolygon, 4326)` and explicitly cast parameter `CAST(:pointGeom AS geography)` in query. |
| **AOT Generic Repository Compilation Failure** | Medium | Micronaut Data annotation processor requires mapped entities and fails on `GenericRepository<Object, UUID>`. | Implement concrete `TimezoneBoundary` domain record annotated with `@MappedEntity("timezone_boundaries")` and `@Serdeable`. |
| **JWKS Key Rotation & Network Flukes** | High | External identity provider outages break authentication tests. | In tests, `OidcMockServer` (WireMock) runs in-process with dynamic port binding, eliminating external network dependencies. |
| **OpenAPI Contract Schema Drift** | High | Unannounced endpoint changes break frontend web and mobile applications. | Automated `ClientContractVerificationSpec` validates generated OpenAPI spec on every build. |

---

## 6. Verification Gates and Release Acceptance Criteria

```
[GIT COMMIT]
      |
      v
+-------------------------------------------------------------+
| GATE 1: Gradle Lighthouse Markdown Audit                    |
| Command: ./gradlew lighthouse                               |
| Criteria: 100% Score on Syntax, Link Integrity, and Anchors  |
+-----------------------------+-------------------------------+
                              | Pass
                              v
+-------------------------------------------------------------+
| GATE 2: Java 25 & Groovy Compilation                        |
| Command: ./gradlew compileJava compileTestGroovy            |
| Criteria: 0 Warnings, Reflection-Free AOT Bytecode Output   |
+-----------------------------+-------------------------------+
                              | Pass
                              v
+-------------------------------------------------------------+
| GATE 3: Comprehensive Spock Test Suite                      |
| Command: ./gradlew test                                     |
| Criteria: 100% Passing Specs (OIDC, 403 Matrix, IDOR,      |
|           Shift Concurrency, Timezones, Client Contract)    |
+-----------------------------+-------------------------------+
                              | Pass
                              v
+-------------------------------------------------------------+
| GATE 4: Code Coverage & Quality Threshold                   |
| Command: ./gradlew jacocoTestReport sonar                   |
| Criteria: >= 85% Branch Coverage on Security/Domain Logic;   |
|           0 Vulnerabilities & 0 Security Hotspots           |
+-----------------------------+-------------------------------+
                              | Pass
                              v
+-------------------------------------------------------------+
| GATE 5: Client Contract Freeze & OpenAPI Export             |
| Command: ./gradlew generateOpenApiSpec                      |
| Criteria: Validated docs/openapi.yaml Handoff Ready         |
+-------------------------------------------------------------+
```

### Gate 1: Lighthouse Markdown Audit

* Command: `./gradlew lighthouse`
* Verification Criteria: Overall Health 100% (`🟢 ALL KOSHER`). Zero formatting errors, zero broken relative links, zero missing code fence blank lines.

### Gate 2: Clean Compilation & Strict Type Safety

* Command: `./gradlew compileJava compileTestGroovy`
* Verification Criteria: Zero compilation errors. No inline FQCNs in Java bodies. Reflection-free AOT metadata generated for all `@Serdeable` classes and `@MappedEntity` records.

### Gate 3: Spock Test Suite Pass Rate

* Command: `./gradlew test`
* Verification Criteria: All test suites pass:
  1. `OidcTokenVerificationSpec` (Nimbus RS256 token signing and claim extraction).
  2. `PrivilegeContainmentSecurityMatrixSpec` (Automated 403 regression suite).
  3. `CrossTenantIsolationSpec` (Multi-tenant IDOR and PostGIS polygon boundaries).
  4. `ConcurrentShiftCapacitySpec` (Zero overbooking under multi-threaded concurrency, connection pool sized to 60, re-registration verified).
  5. `TimezoneSpatialResolutionSpec` (PostGIS `ST_Intersects` offline timezone resolution).
  6. `ClientContractVerificationSpec` (OpenAPI 3.0 schema validation).

### Gate 4: JaCoCo Code Coverage & SonarQube Quality Gate

* Command: `./gradlew jacocoTestReport`
* Verification Criteria: >= 85% branch coverage on all new security services (`ProjectSecurityService`, `ShiftService`, `TimezoneResolutionService`, `AuthentikAuthenticationMapper`). Zero open vulnerabilities on SonarCloud.

### Gate 5: Client Contract Freeze and Handoff

* Command: Exported `docs/openapi.yaml` matches OpenAPI 3.0 specification without schema deviations, enabling frontend web and mobile teams to build client consumers.
