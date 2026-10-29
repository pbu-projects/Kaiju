# Testing Guidelines & Architecture

Guidelines for understanding, contributing to, and maintaining the Kaiju test suite.

> [!NOTE]
> This document explains how testing is structured and maintained in the Kaiju repository. Kaiju's automated test suite implements the formal testing strategy, security rules, and user personas defined in the internal upstream **Field Guide** specification.

---

## 1. Why We Test the Way We Do

Testing in Kaiju is designed around one goal: making sure community volunteers, charity coordinators, and regional directors have a fast, reliable, and trustworthy experience.

* **Real Services, No Fake Mocks**: A volunteer should never have their shift signup fail in production because a test passed against a fake in-memory mock. All tests run against a real [PostgreSQL](https://www.postgresql.org/) database with [PostGIS](https://postgis.net/) enabled in Docker using [Testcontainers](https://testcontainers.com/). What we test is what runs in production.
* **Testing Real Human Actions**: We write tests using [Spock Framework](https://spockframework.org/) following the Given-When-Then pattern. Every test reflects an actual person taking action, such as a volunteer signing up for a shift or a regional director approving a new project.
* **Testing Multiple Situations at Once**: We use data-driven test tables (`@Unroll` and `where:`) to verify many real-world inputs, edge cases, and unexpected mistakes within a single test.
* **Catching All Errors at Once**: We group property checks inside Spock `verifyAll` blocks. If an update has multiple mistakes, the test reports all of them together rather than stopping at the first failure.
* **Testing the Front Door**: Controller tests extend `BaseControllerSpec` and send real HTTP requests to the running application. This verifies that security checks, data formatting, and error messages work properly for mobile apps and web browsers.

---

## 2. Setting Up Test Data Quickly (`KaijuDataBuilder`)

Rather than writing messy SQL scripts by hand, Kaiju uses a type-safe tool called `KaijuDataBuilder`. This tool sets up clean test data in seconds and delivers three practical benefits:

* **Safety During Code Changes**: Builders use real domain classes and repositories. If a field name or type changes in Java, our build tools highlight any broken tests immediately before code is ever merged.
* **Fun, Unique Persona Names**: Builders use [DataFaker](https://www.datafaker.net/) with Star Wars and Zelda character names and unique email addresses. This guarantees that test accounts never collide with each other or overwrite pre-seeded database rows.
* **Automatic Cleanup**: The builder tracks every record created during a test. When the test finishes, `BaseControllerSpec.cleanup()` deletes those records in reverse order (children before parents). This keeps the database clean between tests without needing transaction rollbacks that could interfere with multi-threaded web server tests.
* **Fast Request Payloads**: Builders include `.asCommand()` helpers (such as `ProjectBuilder.asCommand()`). These helpers generate valid JSON request bodies instantly, saving developers from typing repetitive boilerplate payloads in controller tests.

### Available Builders

* `UserBuilder`: Creates volunteers and administrators with specific roles (`GLOBAL_ADMIN`, `STANDARD_USER`) and unique contact details.
* `RegionBuilder`: Creates geographic areas such as states and counties.
* `BoundaryBuilder`: Creates boundary shapes that define regional borders.
* `LocationBuilder`: Creates physical addresses with geographic coordinates.
* `TagBuilder`: Creates searchable tags, such as "Outdoors" or "Youth Friendly".
* `OrganizationBuilder`: Creates charities, non-profits, and community groups.
* `OrganizationUserBuilder`: Adds people to organizations with specific group roles (`ORG_ADMIN`, `ORG_MANAGER`, `ORG_MEMBER`).
* `RegionUserBuilder`: Assigns regional coordinators to administrative areas (`REGION_DIRECTOR`, `REGION_AGENT`).
* `ProjectBuilder`: Creates volunteer projects linked to charities, regions, and physical sites.
* `ShiftBuilder`: Creates project shifts with start times, end times, and volunteer capacity limits.

---

## 3. Location and Geography: Delivering Accurate Search Results

Connecting volunteers with projects in their own neighborhood is at the heart of Kaiju. Spatial tests verify that geographic searches protect the volunteer experience:

* **Finding Opportunities Close to Home (Victor Volunteer)**: When Victor searches for volunteer projects within 5 miles of his home, he must see projects right down the street, and projects 50 miles away must never show up. Tests verify that projects inside his search circle appear, and projects outside his search circle stay out.
* **Respecting Regional Boundaries (Daniel County Director)**: When Daniel reviews projects in Denver County, he must only be able to approve projects physically located within his county. If a project takes place across the county line, the system must turn him away. Tests verify that physical coordinates are strictly checked against official civic boundary lines.
* **Smooth, Predictable Scrolling**: When volunteers scroll through project listings on mobile, the order must remain steady so cards do not shuffle or skip when moving between pages. Tests verify that searches always pair distance ranking with a secondary ID sort (`ORDER BY id ASC`).
* **Volunteers Worldwide**: Community service happens globally. Tests verify realistic international address formats, including letters and numbers in UK and Canadian postal codes, as well as rural locations without postal codes.

---

## 4. Community Hierarchies & Paired Test Personas

Real volunteer organizations and local governments are organized in tiers. Kaiju tests mirror these real-world structures.

### Geographic Levels

1. **Level 1 (State)**: The entire State of Colorado.
2. **Level 2 (County / District)**: Denver County and neighboring El Paso County.
3. **Level 3 (City / Local Area)**: Downtown Denver and Downtown Colorado Springs.

### Organization Levels

1. **Level 1 (National Umbrella)**: National Relief Network (large national organization).
2. **Level 2 (State Chapters)**: Colorado State Chapter and Utah State Chapter.
3. **Level 3 (Local Branches)**: Denver Metro Branch and Colorado Springs Branch.

### Paired Personas for Realistic Teamwork

In real life, coordinators work in teams and hand off tasks. Every administrative level has a primary (`1`) and secondary (`2`) persona. This ensures tests can verify team handoffs, prevent single points of failure, and test concurrent edits:

| Persona Category | Persona Names | System Role | Group Role | Practical Responsibility |
| :--- | :--- | :--- | :--- | :--- |
| **Global Admins** | Sarah Admin 1, Gary Admin 2 | `GLOBAL_ADMIN` | None | Manage system-wide platform settings and emergency escalations |
| **State Directors** | Rachel State Director 1, Robert State Director 2 | `REGION_DIRECTOR` | None | Oversee volunteer initiatives across the entire state |
| **State Agent** | Samuel State Agent | `REGION_AGENT` | None | Review incoming project submissions across the state |
| **County Directors** | Daniel County Director 1, Diana County Director 2 | `REGION_DIRECTOR` | None | Approve projects and verify organizations in Denver County |
| **County Agent** | Connie County Agent | `REGION_AGENT` | None | Help review local projects in Denver County |
| **Sibling Directors** | Ethan Sibling Director 1, Emma Sibling Director 2 | `REGION_DIRECTOR` | None | Manage El Paso County; prove neighboring counties cannot alter each other's work |
| **City Leads** | Clara City Lead 1, Cameron City Lead 2 | `REGION_AGENT` | None | Coordinate grassroots projects in Downtown Denver |
| **National Charity Admins** | Nathan National Admin 1, Nicole National Admin 2 | `STANDARD_USER` | `ORG_ADMIN` | Lead the national charity network |
| **National Operations Manager** | Noah National Manager | `STANDARD_USER` | `ORG_MANAGER` | Configure national schedules and recurring volunteer programs |
| **State Charity Admins** | Steven State Admin 1, Sophia State Admin 2 | `STANDARD_USER` | `ORG_ADMIN` | Manage state-level charity chapters and regional partners |
| **State Operations Manager** | Susan State Manager | `STANDARD_USER` | `ORG_MANAGER` | Coordinate state projects, schedules, and shifts |
| **Sibling State Admins** | Ursula Utah Admin 1, Ulysses Utah Admin 2 | `STANDARD_USER` | `ORG_ADMIN` | Run Utah operations; prove state chapters stay private from each other |
| **Local Branch Admins** | Leo Local Admin 1, Lisa Local Admin 2 | `STANDARD_USER` | `ORG_ADMIN` | Lead the local city branch (Denver Metro Branch) |
| **Local Operations Manager** | Laura Local Manager | `STANDARD_USER` | `ORG_MANAGER` | Manage day-to-day shifts and volunteer rosters locally |
| **Local Branch Member** | Luke Local Member | `STANDARD_USER` | `ORG_MEMBER` | Assist with event check-in and local branch tasks |
| **Community Volunteers** | Victor Volunteer 1, Valerie Volunteer 2 | `STANDARD_USER` | None | Discover nearby projects and sign up for weekend shifts |
| **First-Time Volunteer (JIT)** | Jenny New | `STANDARD_USER` | None | Log in for the very first time; test automatic account setup |

---

## 5. Protecting Community Privacy and Access Boundaries

Tests make sure that sensitive volunteer records and organizational workspaces stay private and secure:

* **Keeping Charity Data Private (Neighboring Group Checks)**: Ethan in El Paso County cannot edit or delete projects belonging to Denver County. Ursula in Utah cannot view or change records belonging to the Colorado Chapter. Tests prove that each group has a private workspace that neighboring groups cannot touch.
* **Respecting Local Leadership Limits**: State directors can look over and support county projects, but local city leads like Clara cannot approve projects taking place outside their assigned downtown zone.
* **Fast and Repeatable Controller Tests**: Controller integration tests create secure session tokens directly using `BaseControllerSpec.asPersona()`. This makes tests run quickly and reliably without sending slow external web requests to log in for every check.
* **End-to-End Login Verification**: In `AuthentikOidcIntegrationSpec`, we test the entire login journey against a live [Authentik](https://goauthentik.io/) server. This proves that real browser redirects, code exchanges, and token signatures work seamlessly.
* **Turning Away Unauthorized Requests**: Tests send expired, tampered, or untrusted login tokens directly to the API, verifying that [Micronaut Security](https://micronaut-projects.github.io/micronaut-security/latest/guide/) turns them away immediately with HTTP `401 Unauthorized` or HTTP `403 Forbidden`.
* **Welcoming First-Time Volunteers (Jenny New)**: When a new volunteer logs into Kaiju for the very first time, the platform automatically creates a standard volunteer profile in PostgreSQL. Tests verify that newcomers can start browsing and signing up for projects right away without hitting registration roadblocks.

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

You can run individual test methods with a single command:

```bash
./gradlew test --tests "*ProjectSecurityMatrixSpec*should enforce matrix*"
```

![Live terminal recording of Spock and Testcontainers running ProjectSecurityMatrixSpec](./testing-call.gif)

> [!TIP]
> **Super Thorough Testing in About a Minute**: While this animation playback is slightly sped up for quick viewing, look at the Gradle timer in the terminal output: the entire real-world lifecycle finishes in about a minute. With one simple command, Testcontainers boots a live PostgreSQL + PostGIS container, Flyway runs 10 database schema migrations from scratch, Spock executes deep security and spatial boundary checks, and the container is cleanly torn down.
>
> You can re-record this animation at any time using [VHS](https://github.com/charmbracelet/vhs) from [`testing-call.tape`](./testing-call.tape).

### View Test Reports

After running tests, open the generated HTML report in your browser:

```text
build/reports/tests/test/index.html
```
