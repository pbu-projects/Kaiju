# Agent Context: Testing Strategy & Documented Behavior
**Domain**: BDD, Spock, Geb, User Journeys, Global Geocoding, UI Matrix Testing
**Context**: This file governs all AI-generated test code for the Volunteer Monster platform.

## 1. The Prime Directive: Documented Behavior > Code Coverage
Test coverage metrics are meaningless. You can achieve 100% line coverage and still deploy a completely broken application if you haven't validated the actual user experience.

- **The Rule:** Tests exist for one reason: to perfectly validate **Documented Behavior** and **Documented User Journeys**.
- **AI Directive:** Before generating a test, you must understand the *intent* of the feature. If a behavior is documented, it must be tested under all supported data variations. Do not write tests just to instantiate a class and assert it isn't null.

---

## 2. The Global Data Strategy (Geocoding & API Testing)
Volunteer Monster explicitly targets a worldwide audience. The platform must handle the chaotic reality of international data.

- **The Non-Trivial Geography Rule:** Do not write tests that only assume standard US addresses (e.g., `123 Main St, City, State, 12345`).
- **Execution for Geocoding & API Tests:** You must explicitly test variations of global location formats to ensure the system succeeds (or fails gracefully) exactly as documented. Your data-driven Spock `where:` blocks must include:
    1. Standard US formatting.
    2. UK/Canadian alphanumeric postal codes.
    3. Nations or rural locations that *do not use postal codes* (e.g., parts of Ireland or the Middle East or parts of South America).
    4. Varying administrative levels (e.g., prefectures vs. states vs. provinces).
- **API Boundary Focus:** API tests (hitting Micronaut `@Controller` endpoints) do not care about browser sizes. They exist to validate JSON serialization boundaries, Role-Based Access Control (RBAC) rejections, and that varying global payloads don't crash the database.

---

## 3. The UI Testing Matrix (Geb)
The UI testing strategy differs fundamentally from the API strategy. Because Volunteer Monster provides a public JTE frontend and an administrative Compose UI, the UI tests must rigorously validate the device and localization matrix.

- **The UI Matrix Rule:** User interface tests must validate the documented layout and behavior across the defined target spectrum.
- **Execution:** When writing Geb/Browser automation tests, the test suite must be configured to iterate through:
    1. **4 Supported Browser Types** (e.g., Chromium, Firefox, WebKit, Edge).
    2. **3 Target Viewport Sizes** (Mobile, Tablet, Desktop).
    3. **All Supported Languages** (Validating that German compound words or Right-To-Left Arabic do not break the CSS grid or documented layout behavior).

---

## 4. Spock & Database Guardrails
When validating documented database or service behavior, you must prevent flakiness and false positives.

### A. Data-Driven BDD (Groovy)
- **Rule:** Use Spock's `@Unroll` and tabular `where:` blocks. If you are testing a login form, pass the success payload, the missing-password payload, and the SQL-injection payload through the *exact same test method* using data tables.

### B. Transaction Rollbacks (No Manual Cleanups)
- **Rule:** NEVER write manual SQL `cleanup:` blocks (e.g., `repository.deleteAll()`).
- **Execution:** Rely exclusively on Micronaut's `@MicronautTest(transactional = true)`. This ensures that every test runs inside an isolated database transaction that automatically rolls back, guaranteeing a pristine state for the next test.

### C. Avoiding Spatial False Positives (The Radius Trap)
- **Rule:** When testing `ST_DWithin` (radius searches) or `ST_Intersects` (boundary searches), it is not enough to assert that the database orders by distance.
- **Execution:** You MUST always insert an explicit "Out of Bounds" coordinate (e.g., placing a location 50km away for a 20km search radius) and assert that it is explicitly excluded from the paginated results.

### D. Pagination Flakiness (The "Noisy Database")
- **Rule:** Never rely on default Postgres ordering if distance or relevance scores tie.
- **Execution:** When writing paginated query tests, always include a deterministic secondary sort (like `ORDER BY id ASC`) after the spatial sort to prevent test flakiness across different build servers.