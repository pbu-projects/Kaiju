# Volunteer Monster MVP Roadmap
**Objective:** Launch a blazing-fast, localized, and self-updating volunteer aggregator to prove operational superiority to enterprise acquisition targets.

## Phase 1: The Spatial Foundation & Engine (Weeks 1-3)
**Goal:** Build a live database populated with thousands of active projects using zero-cost local geocoding.

*   **[ ] Multi-Module Infrastructure:** Finalize the Gradle `core`, `server`, and `sync` boundaries alongside the mobile directories.
*   **[ ] PostGIS Schema:** Implement tables for `Project`, `Shift`, and `Organization`, alongside `boundary_layers` and `boundaries` for spatial fence overrides.
*   **[ ] Identity Management:** Wire Micronaut Security to Keycloak for secure, enterprise-ready authentication.
*   **[ ] The Scraper:** Write the `@Scheduled` Micronaut jobs in the `sync` module to harvest data from UServeUtah and Samaritan Scout.
*   **[ ] Local Geocoding Pipeline:** Build the enrichment logic to inject spatial coordinates into scraped addresses using a static, zero-cost ZIP/City reference database.

## Phase 2: The Public SEO Explorer (Weeks 4-6)
**Goal:** Launch an SEO-optimized, blazing-fast web portal where volunteers can discover opportunities.

*   **[ ] Read-Only APIs:** Expose the `core` data securely via internal controllers.
*   **[ ] JTE Architecture:** Establish the base JTE HTML layouts, headers, and footers for a semantic, server-rendered web UI that guarantees elite SEO ranking.
*   **[ ] The Geographic Fallback:** Implement Cloudflare edge-header reading to passively detect a user's city, falling back to the pilot market if unknown.
*   **[ ] Spatial Search Interface:** Build the feed separating time-bound "Shifts" from flexible "Missions" using PostGIS `ST_DWithin` bounding-box math.
*   **[ ] Deep-Linking:** Ensure all harvested projects link cleanly back to their original source to maintain data attribution.

## Phase 3: The Boundary Admin & Nonprofit "Trojan Horse" (Weeks 7-9)
**Goal:** Deploy operational tooling that incentivizes nonprofits to claim their profiles and manage geographic reporting territories.

*   **[ ] Org Onboarding:** Build the "Claim this Project" authentication flow for coordinators.
*   **[ ] The Click-to-Merge Fencer:** Build the interface allowing admins to select pre-existing ZIP/County polygon layers, running `ST_Union` on the backend to create custom fences.
*   **[ ] The Shift Generator:** Build the backend logic that translates vague ongoing needs into discrete, schedulable micro-shifts.
*   **[ ] The Dashboard:** Create a private JTE view for verified organizations to visualize active shifts, user retention, and territory analytics using `ST_Intersects` reporting.
*   **[ ] Pilot Launch:** Onboard 3-5 local nonprofits to validate the Shift Generator and custom fencing in the real world.

## Phase 4: Mobile Last-Mile & Analytics (Weeks 10-12)
**Goal:** Close the feedback loop natively via mobile devices, proving the platform increases retention and decreases administrative overhead.

*   **[ ] Mobile Rollout:** Compile the shared Compose Multiplatform codebase into native `androidApp` and `iosApp` binaries.
*   **[ ] Volunteer Profiles:** Allow users to log in natively, save their regional preferences, and claim shifts from their devices.
*   **[ ] Automated Comms:** Integrate basic SMS/Email logic for shift reminders and post-event feedback.
*   **[ ] Impact Reporting:** Build the scannable data dashboards nonprofits can export for board meetings.
*   **[ ] M&A Preparation:** Audit the repository for a pristine Git history, strictly enforced CLA signatures, and clean architectural boundaries.