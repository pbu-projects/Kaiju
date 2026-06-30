# Agent Context: Business Logic & Domain Rules
**Domain**: RBAC, Workspaces, Approvals, ETL Sync, Data Ingestion, Authentik IAM
**Context**: This file governs all AI-generated business logic, security enforcement, and automated data ingestion for the Volunteer Monster platform.

## 1. Security & RBAC (The Hybrid Enforcement Flow)
Volunteer Monster uses a decoupled authentication architecture. Do not build authentication flows (like password resets or 2FA) into the Micronaut application.

- **The Division of Labor:** - **Authentik (The IAM):** Handles all identity verification, user registration, and issues the JWT.
    - **Micronaut (The Bouncer):** Reads the JWT and strictly enforces access using Micronaut Security annotations (`@Secured`).
- **The Flat Enum Strategy:** Do not build complex permission matrix tables. Roles are defined by rigid dimensions:
    1. **Platform Roles (`users.role`):** Standard Java Enums (`GLOBAL_ADMIN`, `REGION_ADMIN`, `REGION_MODERATOR`) that govern platform-wide access and queue management.
    2. **Organization Roles (`organization_users.role`):** Standard Java Enums (`ORG_ADMIN`, `ORG_MANAGER`, `ORG_MEMBER`) that govern access to specific local workspaces.

## 2. Workspaces & The Audit Ledger
Every project and user action on the platform must be tied to an organization to support historical auditing.

- **The "Personal Workspace" Rule:** The `organization_id` on a `Project` is strictly `NOT NULL`. If an individual user creates a community project, the system must generate a "Personal Workspace" organization for them behind the scenes, assigning them the `ORG_ADMIN` role for that specific workspace.
- **The "State + Ledger" Rule (M&A Compliance):** State changes regarding platform trust must never happen in isolation. If you generate Micronaut code to update `organizations.verification_status = 'VERIFIED'`, you MUST use `@Transactional` to simultaneously insert a permanent record into `organization_audit_logs` capturing the `previous_status`, `new_status`, and `reason`.
- **Immutable Domain Objects:** Java records representing business entities must be immutable. NEVER generate traditional getter/setter methods. When updating an object, use the **Wither Pattern** (e.g., `project.withStatus("APPROVED")`) to generate a new instance before saving.

## 3. The Geographic Escalation Queue (Community Approvals)
To avoid the administrative nightmare of assigning tasks to specific users, Volunteer Monster routes unverified community projects based on PostGIS physical reality.

- **The Verification Routing Switch:** Organization trust is an expiration-backed lifecycle (`UNVERIFIED`, `PENDING_REVIEW`, `VERIFIED`, `SUSPENDED`, `REVOKED`), not a boolean.
    - If a project belongs to an organization where `verification_status = 'VERIFIED'`, the project is auto-approved.
    - If `verification_status` is anything else, it drops into the Geographic Escalation Queue.
- **Queue Execution:**
    1. PostGIS determines what administrative boundary the project's coordinate falls into and sets the `managing_region_id` to that boundary.
    2. Any `REGION_MODERATOR` mapped to that region can approve it.
    3. **The Cron Escalation:** If the project sits unapproved for 48 hours, a background job automatically updates the `managing_region_id` to the parent region, bumping it up the chain.
- **The Contextual Re-Approval Rule:** If a standard user (lacking platform trust) edits a project that is already `ACTIVE`, the Micronaut backend must intercept the update, save the changes, but automatically flip the project's status back to `PENDING_UPDATE`, throwing it back into the queue.

## 4. The Sync Module & ETL Engine (Web Scraping)
The `sync` module harvests data from external aggregators. Legacy data is highly unpredictable.

- **The Forgiving Ingestion Rule:** If database constraints are too strict, the nightly sync will crash on messy legacy data. For scraped content, configure Micronaut entities to use unbounded `TEXT` or highly forgiving `VARCHAR` limits. Let the application apply a "Quarantine" status (`FLAGGED`) for suspiciously large payloads rather than throwing SQL exceptions.
- **Drop, Don't Save:** If an aggregator provides a spoofed, invalid, or missing geographic coordinate, the ingestor must drop/skip the record entirely. The platform relies on spatial reality; a project without a valid PostGIS coordinate is useless. Do not write brittle regex parsers trying to guess locations.