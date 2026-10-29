# Testing Guidelines & Architecture

Guidelines for understanding, contributing to, and maintaining the Kaiju test suite.

> [!NOTE]
> This document explains how testing is structured and maintained in the Kaiju repository. Kaiju's automated test suite implements the formal testing strategy, security rules, and user personas defined in the internal upstream **Field Guide** specification.

---

## 1. Core Testing Principles

Kaiju uses [Spock Framework](https://spockframework.org/) and [Testcontainers](https://testcontainers.com/) to test against real infrastructure rather than hollow in-memory mocks.

* **Real Services Over Mocks**: All integration tests run against a live [PostgreSQL](https://www.postgresql.org/) database with [PostGIS](https://postgis.net/) enabled inside a Docker container. We do not use mock databases or in-memory approximations.
* **Behavior-Driven Structure**: Tests follow the Given-When-Then pattern. We write tests to verify real user journeys, edge cases, and security boundaries rather than chasing arbitrary code coverage percentages.
* **Data-Driven Tables**: We use Spock `@Unroll` and `where:` tables to test multiple inputs, boundary conditions, and failure modes cleanly within a single test method.
* **Grouped Assertions**: We use Spock `verifyAll` blocks whenever asserting multiple fields on an object or response. This ensures all field failures are reported together instead of stopping at the very first failing check.
* **Live HTTP Server Integration**: Controller specs extend `BaseControllerSpec` and use `BlockingHttpClient` against the running embedded server, verifying the complete Micronaut filter chain and serialization pipeline.

---

## 2. Test Fixture Builders (`KaijuDataBuilder`)

Rather than maintaining fragile static SQL scripts or scattering raw `INSERT` queries across test files, Kaiju uses a fluent, type-safe builder suite rooted in `KaijuDataBuilder`.

### Key Benefits

* **Refactoring Safety**: Builders use real domain models and repositories. When a model field changes, compile-time checks catch test mismatches instantly.
* **Collision-Free Personas**: Builders use [DataFaker](https://www.datafaker.net/) with Star Wars and Zelda character names and unique email suffixes. Tests never collide with each other or with pre-seeded database rows.
* **Tracked Entity Cleanup**: The builder tracks every entity ID created during a test. In `BaseControllerSpec.cleanup()`, all tracked entities are deleted in reverse order (child records before parent records). This provides clean isolation across multi-threaded HTTP test runs without needing database transaction rollbacks.
* **Command DTO Helpers**: Builders provide `.asCommand()` methods (such as `ProjectBuilder.asCommand()`) to generate valid request payloads for testing HTTP endpoints directly.

### Available Builders

* `UserBuilder`: Creates users with specific system roles (`GLOBAL_ADMIN`, `STANDARD_USER`) and unique contact details.
* `RegionBuilder`: Creates geographic administrative regions.
* `BoundaryBuilder`: Creates geographic boundary polygons linked to administrative regions.
* `LocationBuilder`: Creates physical locations with JTS point coordinates.
* `TagBuilder`: Creates searchable category tags.
* `OrganizationBuilder`: Creates community groups and partner charities.
* `OrganizationUserBuilder`: Assigns users to organizations with group roles (`ORG_ADMIN`, `ORG_MANAGER`, `ORG_MEMBER`).
* `RegionUserBuilder`: Assigns users to administrative regions with regional roles (`REGION_DIRECTOR`, `REGION_AGENT`).
* `ProjectBuilder`: Creates volunteer projects linked to organizations, regions, and locations.
* `ShiftBuilder`: Creates project shifts with start times, end times, and volunteer capacity limits.

---

## 3. Spatial & Geographic Boundary Rules

Location-based volunteer discovery is a core capability of Kaiju. Spatial tests follow strict rules to prevent geometry bugs:

* **Inclusion and Exclusion Checks**: When testing geographic radius searches (`ST_DWithin`) or checking whether a point falls inside a region boundary (`ST_Contains`), tests must always verify both matching points and known out-of-bounds coordinates to prove exclusion.
* **Consistent Sorting Order**: When checking paginated queries, tests must pair spatial sorting with an explicit primary key order (such as `ORDER BY id ASC`) to prevent flaky tie-breaking.
* **International Address Support**: Tests include realistic global formats, including alphanumeric postal codes and locations without postal codes.

---

## 4. Three-Tier Hierarchies & Paired Test Personas

Security, regional approvals, and moderation rely on clear, three-tier hierarchies.

### Geographic Regional Hierarchy

1. **Level 1 (State)**: Colorado State Region encloses the entire state.
2. **Level 2 (County / District)**: Denver County Region and El Paso County Region (both children of Colorado State).
3. **Level 3 (City / Local Area)**: Denver Downtown Core and Colorado Springs Downtown.

### Organizational Hierarchy

1. **Level 1 (National Network)**: National Relief Network (broad umbrella organization).
2. **Level 2 (State Chapter)**: Colorado State Chapter and Utah State Chapter (children of National Relief Network).
3. **Level 3 (Local Branch)**: Denver Metro Branch and Colorado Springs Branch.

### Paired Administrator Personas

To prevent single points of failure in test data and enable realistic multi-user tests, each tier pairs a primary (`1`) and secondary (`2`) persona.

System roles (`UserRole`) are distinct from scoped roles (`OrganizationUserRole` / `RegionUserRole`). All organization leads have a base system role of `STANDARD_USER` and hold scoped permissions through their organization memberships:

| Category | Persona Names | System Role | Scoped Role | Scope / Jurisdiction |
| :--- | :--- | :--- | :--- | :--- |
| **Global Admins** | Sarah Admin 1, Gary Admin 2 | `GLOBAL_ADMIN` | None | System-wide administrative controls |
| **State Region** | Rachel State Director 1, Robert State Director 2 | `REGION_DIRECTOR` | None | Colorado State Region |
| **State Agent** | Samuel State Agent | `REGION_AGENT` | None | Colorado State Region |
| **County Region** | Daniel County Director 1, Diana County Director 2 | `REGION_DIRECTOR` | None | Denver County Region |
| **County Agent** | Connie County Agent | `REGION_AGENT` | None | Denver County Region |
| **Sibling County** | Ethan Sibling Director 1, Emma Sibling Director 2 | `REGION_DIRECTOR` | None | El Paso County Region |
| **City Core** | Clara City Lead 1, Cameron City Lead 2 | `REGION_AGENT` | None | Denver Downtown Core |
| **National Org Admin** | Nathan National Admin 1, Nicole National Admin 2 | `STANDARD_USER` | `ORG_ADMIN` | National Relief Network |
| **National Org Manager** | Noah National Manager | `STANDARD_USER` | `ORG_MANAGER` | National Relief Network |
| **State Org Admin** | Steven State Admin 1, Sophia State Admin 2 | `STANDARD_USER` | `ORG_ADMIN` | Colorado State Chapter |
| **State Org Manager** | Susan State Manager | `STANDARD_USER` | `ORG_MANAGER` | Colorado State Chapter |
| **Sibling Org Admin** | Ursula Utah Admin 1, Ulysses Utah Admin 2 | `STANDARD_USER` | `ORG_ADMIN` | Utah State Chapter |
| **Local Org Admin** | Leo Local Admin 1, Lisa Local Admin 2 | `STANDARD_USER` | `ORG_ADMIN` | Denver Metro Branch |
| **Local Org Manager** | Laura Local Manager | `STANDARD_USER` | `ORG_MANAGER` | Denver Metro Branch |
| **Local Org Member** | Luke Local Member | `STANDARD_USER` | `ORG_MEMBER` | Denver Metro Branch |
| **Volunteers** | Victor Volunteer 1, Valerie Volunteer 2 | `STANDARD_USER` | None | Independent community volunteers |
| **Newcomer (JIT)** | Jenny New | `STANDARD_USER` | None | Unregistered identity, provisions on first login |

---

## 5. Cross-Boundary Security & Data Isolation

Tests verify authorization across groups and regional boundaries:

* **Horizontal Isolation (Sibling Checks)**: Verifies that Ethan and Emma (El Paso County) cannot approve or modify projects in Denver County, and Ursula and Ulysses (Utah Chapter) cannot alter projects in the Colorado Chapter.
* **Vertical Boundaries**: Verifies that higher-tier directors can oversee sub-regions, while local leads (such as Clara in Denver Downtown) cannot approve projects outside their local boundary.
* **Controller Authentication**: Controller integration tests generate signed session tokens using `BaseControllerSpec.asPersona()`. This provides fast, isolated, and repeatable test runs without making slow external login calls.
* **Live Identity Provider Verification**: The full authentication flow (handling login forms, code redirects, token exchanges, and cryptographic signature checks) is tested against a live [Authentik](https://goauthentik.io/) instance in `AuthentikOidcIntegrationSpec`.
* **Front-Door Rejection**: Submits expired, tampered, or untrusted tokens directly over HTTP to confirm that [Micronaut Security](https://micronaut-projects.github.io/micronaut-security/latest/guide/) returns HTTP `401 Unauthorized` or HTTP `403 Forbidden`.
* **Just-In-Time (JIT) Provisioning**: Validates that new identities (such as Jenny New) automatically receive a valid `STANDARD_USER` profile in PostgreSQL upon their first successful login.

---

## 6. Running Tests Locally

### Prerequisites

1. Make sure Docker is running on your machine.
2. Start the local Authentik identity provider:

```bash
docker compose -f database/compose.yml up -d authentik-server authentik-worker
```

### Run All Tests

```bash
./gradlew test
```

### Run a Specific Specification

```bash
./gradlew test --tests "*ProjectSecurityMatrixSpec*"
```

### Run a Single Test Method

```bash
./gradlew test --tests "*ProjectSecurityMatrixSpec*should enforce matrix*"
```

### View Test Reports

After running tests, open the generated HTML report in your browser:

```text
build/reports/tests/test/index.html
```
