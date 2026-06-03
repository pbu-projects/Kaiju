# Volunteer Monster MVP Roadmap
**Objective:** Launch a blazing-fast, localized, and self-updating volunteer aggregator to prove operational superiority to enterprise acquisition targets.

## Phase 1: The "Cold Start" Engine (Weeks 1-3)
**Goal:** Build a live database populated with thousands of active projects without any manual data entry.

*   **[ ] Multi-Module Infrastructure:** Finalize the Gradle `core`, `server`, and `sync` boundaries.
*   **[ ] Database Schema:** Design the PostGIS-enabled Postgres tables for `Project`, `Shift`, and `Organization`.
*   **[ ] Identity Management:** Wire Micronaut Security to Keycloak for secure, enterprise-ready authentication.
*   **[ ] The Scraper:** Write the `@Scheduled` Micronaut jobs in the `sync` module to harvest data from UServeUtah and Samaritan Scout.
*   **[ ] Enrichment Pipeline:** Build the geocoding logic to inject spatial coordinates into scraped addresses.

## Phase 2: The Public Explorer (Weeks 4-6)
**Goal:** Launch an SEO-optimized, blazing-fast web portal where volunteers can discover opportunities.

*   **[ ] Read-Only APIs:** Expose the `core` data securely via internal controllers.
*   **[ ] JTE Architecture:** Establish the base JTE HTML layouts, headers, and footers for the web UI.
*   **[ ] The Geographic Fallback:** Implement Cloudflare edge-header reading to passively detect a user's city, falling back to the pilot market if unknown.
*   **[ ] The Search Interface:** Build the feed separating time-bound "Shifts" from flexible "Missions".
*   **[ ] Deep-Linking:** Ensure all harvested projects link cleanly back to their original source to maintain data attribution.

## Phase 3: The Nonprofit "Trojan Horse" (Weeks 7-9)
**Goal:** Deploy operational tooling that incentivizes nonprofits to claim their profiles and manage data natively.

*   **[ ] Org Onboarding:** Build the "Claim this Project" authentication flow for coordinators.
*   **[ ] The Shift Generator:** Build the UI and backend logic that translates vague ongoing needs into discrete, schedulable micro-shifts.
*   **[ ] The Dashboard:** Create a private JTE view for verified organizations to visualize their active shifts and volunteer capacity.
*   **[ ] Pilot Launch:** Onboard 3-5 local nonprofits to validate the Shift Generator in the real world.

## Phase 4: Volunteer Last-Mile & Analytics (Weeks 10-12)
**Goal:** Close the feedback loop, proving the platform increases retention and decreases administrative overhead.

*   **[ ] Volunteer Profiles:** Allow users to log in, save their regional preferences, and claim shifts.
*   **[ ] Automated Comms:** Integrate basic SMS/Email logic for shift reminders and post-event feedback.
*   **[ ] Impact Reporting:** Build the scannable data dashboards nonprofits can export for board meetings.
*   **[ ] M&A Preparation:** Audit the repository for a pristine Git history, strictly enforced CLA signatures, and clean architectural boundaries.
