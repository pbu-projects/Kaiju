# Agent Context: Database & PostGIS Strategy
**Domain**: PostgreSQL, PostGIS, Relational Schema, Audit Logs, Spatial Queries
**Context**: This file governs all AI-generated database migrations, SQL queries, and Micronaut Data DTO mappings for the Volunteer Monster platform.

## 1. Core Database Design Priorities
When proposing schema changes or writing complex SQL, the AI must weigh decisions against these priorities in order:

1.  **Spatial Performance First:** Rely entirely on PostGIS native indexing (`GIST`) and native functions (`ST_DWithin`, `ST_Intersects`) for spatial filtering. NEVER pull raw coordinates into the Micronaut Java layer to perform distance or boundary math in memory.
2.  **Relational Simplicity (Flat is Better):** Favor flat table structures utilizing discriminator columns (e.g., `project_type` ENUMS) over deep inheritance mappings or complex polymorphic joins.
3.  **Strict Write, Forgiving Read:** Database constraints for internal application data must be rigidly normalized. However, columns designated for *scraped/ETL data* (e.g., text descriptions from JustServe) must use unbounded `TEXT` or generous `VARCHAR` limits to prevent the ingestion pipeline from crashing on legacy data.
4.  **Decouple Reality from Relationships:** Physical locations (lat/lon) are distinct from the organizations that temporarily occupy them. Schema design must reflect this reality to support M&A (Mergers & Acquisitions) grade auditing.

---

## 2. PostGIS & Spatial Rules

### The `GEOGRAPHY` Rule
- **Rule:** ALL geographic points must be stored as `GEOGRAPHY(Point, 4326)`.
- **Why:** `GEOMETRY` calculates distance in meaningless Cartesian degrees. `GEOGRAPHY` natively calculates distance mathematically in **meters**, accounting for the curvature of the earth.
- **AI Directive:** Do not hallucinate EPSG:3857 or standard `GEOMETRY` types. When writing radius queries, always use `WHERE ST_DWithin(location, target, distance_in_meters)`.

### Spatial Fencing & Escalation
- **Rule:** Use `ST_Intersects` (Point-in-Polygon spatial joins) when determining if a `Project`'s location falls within a specific `administrative_region` or `boundary`.
- **AI Directive:** Do not rely on hardcoded foreign keys to map a project to a city/region. The project's raw coordinate dictates its jurisdiction dynamically via PostGIS.

---

## 3. Core Architectural Patterns

### The Umbrella Pattern (Bridge Tables)
- **Rule:** Never put coordinate data directly on the `projects` or `organizations` tables.
- **Execution:** Always utilize bridge tables (`project_locations`, `organization_locations`). This ensures a single organization can have multiple headquarters, or a single city-wide service project can have five distinct starting rally points without duplicating project metadata.

### The Discriminator Pattern
- **Rule:** Differentiate varying business rules using ENUM discriminator columns rather than splitting entities into separate tables.
- **Execution:** A project might be a standard event, an open-door ongoing need, or a regional billboard. Store all of these in the `projects` table using a `project_type` column (e.g., `STANDARD`, `OPEN_DOOR`, `REGIONAL_BILLBOARD`) to dictate how the application processes them.

---

## 4. Audit & Historical Ledger Strategy
Enterprise-grade platforms must never lose the "Who, What, and When" of a data change.

### A. Immutable Physical Reality
- **Rule:** Records in the `locations` table are **immutable**.
- **Execution:** If the "Red Cross" moves out of "123 Main St", you **delete the bridge record** in `organization_locations`. You DO NOT delete the row in `locations`. The physical building still exists, and preserving the coordinate pin guarantees that historical audit logs for old shifts accurately point to where the event actually occurred.

### B. Soft vs. Hard Deletes
- **Hard Deletes:** Only permitted on bridge tables (e.g., `organization_users`, `project_locations`) to break a relationship.
- **Soft Deletes / Status Flags:** Use standard `status` Enums (e.g., `ACTIVE`, `INACTIVE`, `DRAFT`, `FLAGGED`) for domain entities.

### C. Explicit Audit Ledgers
- **Rule:** For high-liability or complex state changes (like vetting organizations or approving community projects), use dedicated audit tables rather than trying to stuff audit history into JSONB columns.
- **Execution:** Tables like `organization_audit_logs` must explicitly record:
    1. `target_entity_id` (What was changed)
    2. `actor_user_id` (Who changed it)
    3. `previous_state` (e.g., UNVETTED)
    4. `new_state` (e.g., APPROVED)
    5. `timestamp`
- **AI Directive:** When writing Micronaut Data save operations for vetted statuses, always mandate the generation of the accompanying audit log record in the same database transaction.