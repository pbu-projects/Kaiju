# Adversarial Code Review: Volunteer Discovery UI

**Branch under review**: `feat/volunteer-discovery-ui` compared to `main`  
**Reviewer Role**: Adversarial Framework Architect  
**Final Verdict**: **REJECTED / BLOCKED** (Fixes required before merge)

---

## 1. Executive Summary

The changes introduce a volunteer project discovery web page and seed real Colorado volunteer projects for local testing.

While the Java controller (`DiscoveryViewController.java`) is simple, reflection-free, and thread-safe, this branch cannot be merged in its current state. It contains a critical credential leak in configuration, an unescaped string bug that causes browser script crashes and security risks, and severe client-side data architecture shortcuts.

---

## 2. Blocking Issues (Must Fix Before Merge)

### Issue 1: Hardcoded Cleartext Password and Username in `application.yml`
* **File**: [application.yml](file:///home/jimmy/git/pbu/Kaiju/src/main/resources/application.yml#L41-L42)
* **Severity**: **BLOCKER (Security)**
* **Detail**:
  The database credentials in `application.yml` were changed from:
  ```yaml
  username: ${DB_USERNAME:postgres}
  password: ${DB_PASSWORD:}
  ```
  to:
  ```yaml
  username: ${DB_USERNAME:jimmy}
  password: ${DB_PASSWORD:warm-farts-smell-worse}
  ```
* **Impact**:
  1. Personal developer usernames and vulgar plain-text passwords must never be committed to source control.
  2. Any environment started without explicit environment variables will use these credentials or fail connection checks.
* **Remediation**:
  Revert `application.yml` back to standard empty or generic defaults (`postgres` and `${DB_PASSWORD:}`). Keep local environment passwords in untracked local environment files or shell variables.

---

### Issue 2: Unescaped String Interpolation and Syntax Crash in `discovery.html`
* **File**: [discovery.html](file:///home/jimmy/git/pbu/Kaiju/src/main/resources/views/discovery.html#L1403)
* **Severity**: **BLOCKER (Security & Usability)**
* **Detail**:
  In `openProjectModal()`, dynamic strings from the database are placed directly into an inline JavaScript attribute:
  ```javascript
  <button type="button" class="primary-btn" onclick="alert('Signed up for ' + '${site.name}')">Select Shift</button>
  ```
  In `database/init/13-seed-justserve-poc-projects.sql`, location names and project titles include apostrophes, such as:
  ```sql
  'Denver - 2026 Children''s Holiday Party'
  ```
* **Impact**:
  1. **Script Crash**: When a user clicks to view or select a shift with an apostrophe in its name (`Children's`), the browser parses the single quote as the end of the string. It throws `Uncaught SyntaxError: Unexpected identifier 's'` and stops working.
  2. **Cross-Site Scripting (XSS)**: In `renderCard()`, project titles and organization names are injected directly into `innerHTML` without HTML escaping:
     ```javascript
     <div class="card-line-2">${item.projectTitle}</div>
     <div class="card-line-3">Hosted by ${item.organizationName}</div>
     ```
     If an organization name or project title contains malicious HTML or script tags, it executes in the user's browser context.
* **Remediation**:
  1. Do not use inline `onclick="... '${var}'"` string construction. Use DOM event listeners (`addEventListener`) or pass identifiers via `data-*` attributes.
  2. Sanitize and escape all text before setting `innerHTML`, or set `textContent` on DOM nodes directly.

---

## 3. High-Priority Architectural Critique

### 1. Hardcoded Data Catalogs in the View Template
* **File**: [discovery.html](file:///home/jimmy/git/pbu/Kaiju/src/main/resources/views/discovery.html#L869-L957)
* **Detail**:
  The view template contains over 100 lines of hardcoded JavaScript state:
  - `KNOWN_LOCATIONS`: 8 hardcoded Colorado coordinates.
  - `LOCATION_CATALOG`: 30 hardcoded UUIDs mapped to addresses and coordinates.
  - `PROJECT_ENRICHMENT`: Hardcoded UUID mappings for tags, seats, and themes.
  - `REGIONAL_OPPORTUNITIES`: Hardcoded mock regional volunteer objects.
* **Problem**:
  The client has to hardcode these catalogs because the backend search endpoint (`/projects/search-by-location`) returns a small projection `ProjectSearchCard` that only includes `(projectId, projectTitle, locationId, locationName, nextShiftStart)`. It omits latitude, longitude, city, state, and organization name.
* **Consequence**:
  Any new project added to the database by users will not exist in `LOCATION_CATALOG`. New projects fall back to placing their map marker at the center of the search circle.
* **Recommendation**:
  Update `ProjectSearchCard` (or provide an enriched discovery search DTO) so the backend returns the necessary display fields: latitude, longitude, city, state, organization name, and category. Remove the hardcoded catalog dictionaries from the HTML template.

---

### 2. Client-Side N+1 HTTP Request Storm
* **File**: [discovery.html](file:///home/jimmy/git/pbu/Kaiju/src/main/resources/views/discovery.html#L1052-L1068)
* **Detail**:
  After calling `/projects/search-by-location`, the client script attempts to fill in missing details:
  ```javascript
  const details = await Promise.all(
      missingIds.map((id) =>
          fetch(`/projects/${encodeURIComponent(id)}`, ...)
      )
  );
  ```
* **Problem**:
  If a search returns 50 projects, the browser sends 51 separate HTTP requests to the backend server in parallel.
* **Consequence**:
  This creates high network overhead and slows down page rendering on mobile networks.
* **Recommendation**:
  Return all necessary card information in the search endpoint response so a single HTTP call delivers all data needed to render the search cards.

---

### 3. Direct Browser Calls to Third-Party Geocoding
* **File**: [discovery.html](file:///home/jimmy/git/pbu/Kaiju/src/main/resources/views/discovery.html#L1020-L1034)
* **Detail**:
  The client browser calls OpenStreetMap Nominatim directly (`https://nominatim.openstreetmap.org/search`) without a custom User-Agent.
* **Problem**:
  1. The OpenStreetMap Nominatim Usage Policy requires a custom User-Agent identifying the application. Generic browser fetches without an identification header are subject to immediate IP rate-limiting and blocking.
  2. Direct browser fetches leak user search terms directly to third-party endpoints.
* **Recommendation**:
  Provide an internal proxy or geocoding resolution endpoint on the backend, or validate addresses using Kaiju's existing spatial resolution services.

---

### 4. Unnecessary Runtime Dependency
* **File**: [build.gradle.kts](file:///home/jimmy/git/pbu/Kaiju/build.gradle.kts#L64)
* **Detail**:
  `runtimeOnly("org.yaml:snakeyaml")` was added to `build.gradle.kts` as an unstaged change without a clear requirement.
* **Recommendation**:
  Micronaut handles YAML configuration via its standard runtime modules. Verify if `snakeyaml` is truly required. If not, remove it to keep dependencies lean.

---

## 4. Strengths & Verified Standards

1. **Controller Architecture (`DiscoveryViewController.java`)**:
   - Clean Micronaut controller configured with `@Secured(IS_ANONYMOUS)` and `@Controller("/")`.
   - Hidden from public REST OpenAPI documentation via `@Hidden` annotations.
   - Zero reflection: Template file is loaded once during startup and stored in an immutable `final String discoveryHtml`. Request handling takes constant time with zero wasteful memory allocations or disk reads.
   - Clean imports: Zero inline fully qualified domain names (FQDNs), clean package structures.
2. **Database Migration (`13-seed-justserve-poc-projects.sql`)**:
   - Uses PostGIS `GEOGRAPHY(Point, 4326)` with `ST_GeographyFromText('POINT(lon lat)')`. Longitude and latitude ordering is correct.
   - Fully idempotent: Uses `ON CONFLICT (id) DO NOTHING` across organizations, locations, projects, and shifts.
   - Preserves referential integrity across foreign key relations.
3. **Automated Testing (`DiscoveryViewControllerSpec.groovy`)**:
   - Uses Spock `verifyAll` blocks for all multi-assertion checks.
   - Verifies both standard anonymous HTTP 200 delivery and 500 error handling on missing template resources.
   - Passes all test suite validations without errors.

---

## 5. Summary of Required Actions

Before this pull request can be approved and merged, complete the following:

1. **Revert database credentials in `application.yml`**: Remove hardcoded passwords and developer usernames.
2. **Fix string escaping in `discovery.html`**:
   - Remove inline `onclick` string concatenation to fix the crash with apostrophes in location names.
   - Use safe DOM text insertion or escaping to eliminate XSS risks.
3. **Plan backend DTO enhancement**: File a ticket to enrich `ProjectSearchCard` with coordinates, city, and organization name to eliminate the hardcoded template catalogs and client-side N+1 HTTP calls.

---

## 6. Resolution of Required Actions (Verified)

All blocking issues have been resolved:

1. **Database credentials in `application.yml`**: Fully reverted to standard defaults (`${DB_USERNAME:postgres}` and `${DB_PASSWORD:}`).
2. **String escaping and apostrophe handling in `discovery.html`**:
   - Added `escapeHtml()` utility to sanitize all project titles, city labels, organization names, and tags.
   - Replaced inline string interpolation in shift modal buttons with safe `data-site-name` attributes and DOM event delegation (`handleShiftSelect(this)`).
3. **DTO Enhancement Tracking**: Logged in GitHub issues #124, #125, #126, and #127 for backend enrichment.

**Updated Verdict**: **APPROVED FOR PULL REQUEST SUBMISSION**
