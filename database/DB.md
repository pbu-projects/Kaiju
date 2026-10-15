# Database Architecture

This directory contains the PostgreSQL + PostGIS schema and initialization scripts for Volunteer Monster.

### `locations` Table

Stores the exact points where volunteer work physically takes place.

- Rule: ONLY contains exact coordinates (GEOGRAPHY(Point,[4326])). No polygons.
- Global Rules: Uses ISO 3166-1 alpha-2 country_code. state_province and postal_code are nullable to support
  international addresses (e.g., UAE, parts of Chile).
- Reading: [PostGIS Geography Type] | [ISO 3166-1 alpha-2]

### `boundaries` Table

> [!WARNING]
> Keep this out of the project searches of any kind.
>
Defines large regional areas and jurisdictions used strictly for administrative reporting and geofencing. This is
separated from the main search to keep the main search performant

- Rule: ONLY contains shapes (`GEOGRAPHY(Polygon)`). Used for admin reporting or strict geographic constraints (e.g., "
  Must reside in Davis County").
- Reading: [PostGIS ST_Intersects]

## The Project Ecosystem

### `projects` Table

Acts as the central umbrella record defining the details of a volunteer opportunity, regardless of its physical location
or timing.

- Uses a `project_type` [discriminator] (`STANDARD`, `OPEN_DOOR`, `REGIONAL`) to dictate UI
  rendering and search logic without needing multiple tables.
- Implements soft deletes (`deleted_at`, `deleted_by`) for moderating
- Reading: [Single Table Inheritance Pattern]

### `project_locations` Join Table

Enables an project organizer to host a single project across dozens of different physical addresses without duplicating
the main project details.

- Links a single umbrella project (e.g., "Statewide Park Cleanup") to many physical locations. Prevents admins from
  needing to create multiple duplicate projects, which would clutter the volunteer's search for projects.

### `project_boundaries` Table

Each entry assigns a project to a massive regional area when a specific street address cannot be provided, such as a
statewide disaster response.

- Links a project to a polygon (e.g., "Victoria Bushfire Response"). Handled via a dual-track search to provide honest
  UI rendering (no fake map pins).

## Shifts & Execution

### `shifts` Table

Holds the specific time slots, dates, and volunteer capacity limits that users actually sign up for.

- Remote Logic: If location_id IS NULL, the shift is inherently Remote/Virtual. No extra columns needed.
- Pagination: Indexed on start_time and id to enforce high-performance cursored (keyset) pagination. Bypasses `OFFSET`
  completely.
- Reading: [Why OFFSET is Bad (Keyset Pagination)]

### `tags` & `shift_tags` Tables

Applies searchable text filters to shifts, which is critical for organizing and classifying virtual or at-home
opportunities.

- Used to categorize shifts, primarily differentiating types of remote work (e.g., `VIRTUAL/ONLINE` vs `AT_HOME_DIY`).

## Administration & Auditing

### `users` Table

Holds the application-level profile data and authorization roles, completely decoupled from the external IAM
authentication database.

- The application-layer user record. Strictly defines the role hierarchy (`SUPER_ADMIN`, `MODERATOR`,
  `ORGANIZATION_LEADER`, `VOLUNTEER`) for Micronaut @Secured enforcement.

### `project_audit_logs` Table

Provides an immutable historical trail of who created, edited, or approved a project, ensuring strict moderation
controls over project submissions.

- An append-only ledger tracking every major action (Created, Approved, Rejected, Edited). Ensures the moderators can
  find data somewhere if it's eventually needed
- Reading: [Event Sourcing & Audit Logs]

## Dual-Track Geospatial Strategy: Points vs. Regional Polygons

Our spatial architecture separates geographic data into two distinct tracks to preserve database query speed, avoid misleading user interfaces, and enforce strict legal jurisdictions:

1. **Exact Work Sites (`locations` Table)**:
   * **Shape**: Exclusively stores exact points (`GEOGRAPHY(Point, 4326)`).
   * **Purpose**: Represents physical addresses where volunteers gather (e.g., a community center, a city park, or a school).
   * **Why Points**: Local volunteer discovery needs to be fast. Storing only points in `locations` allows spatial radius queries to scan lightweight coordinates without touching heavy, multi-thousand-vertex boundary polygons.

2. **Administrative Jurisdictions & Broad Initiatives (`administrative_regions` & `project_boundaries` Tables)**:
   * **Shape**: Exclusively stores authoritative shapes (`GEOGRAPHY(Geometry, 4326)` constrained to `Polygon` and `MultiPolygon`).
   * **Purpose**: Defines municipal and county administrative oversight zones, volunteer management chapters, and broad initiatives that lack a single street address (such as statewide wildfire relief).
   * **Why Polygons Cannot Be Avoided**:
     * **Legal Permit & Approval Containment**: A regional leader only has legal authority to approve volunteer projects within their assigned territory. PostGIS evaluates exact polygon containment (`ST_Covers`) to ensure approvals cannot spill into neighboring municipalities.
     * **Non-Circular Municipal Geography**: Real municipal borders follow rivers, county lines, and irregular corridors. For example, Denver County connects to its airport via an 18-mile corridor. A circular radius or rectangular box either cuts off real community members or oversteps into surrounding cities. Only `MultiPolygon` shapes represent these jurisdictions honestly.
     * **Automated Geographic Routing**: When a project is created at an address point, PostGIS containment queries instantly determine the parent municipality, county, and state chapter, avoiding manual tagging errors.

### Two-Phase PostGIS Spatial Indexing

You might wonder if storing polygons with thousands of coordinate points slows down lookups. PostGIS solves this through a **two-phase index strategy** using Generalized Search Tree (GiST) spatial indexing:

1. **Phase 1 (Bounding Box Index - Microseconds)**: PostGIS automatically calculates a rectangular bounding envelope for every polygon. When checking if a coordinate is inside a region, it checks the bounding box index first. This instantly eliminates 99.9% of candidate records without doing expensive geometric math.
2. **Phase 2 (Exact Boundary Test - True Precision)**: Only for the few candidates that fall inside the bounding box does PostGIS run the detailed topological check (`ST_Covers` or `ST_Intersects`).

This gives us the best of both worlds: the lightning-fast speed of rectangular index passes with the exact legal accuracy of authoritative US Census boundaries.

## Key Queries: Three-Tier Search Progression

When volunteers search for opportunities, the platform resolves results through a three-tier spatial progression:

1. **Tier 1 (The Local Radius Search)**:
   * Used when a volunteer searches near home (e.g., within 5, 10, or 25 miles).
   * Query: `ST_DWithin(locations.geom, :user_point, :radius_meters)`
   * Scans lightweight point coordinates using GiST indexes for maximum speed.
2. **Tier 2 (The Regional Boundary Match)**:
   * Used for broad community campaigns or emergency initiatives that cover an entire district without a single street pin.
   * Query: `ST_Intersects(project_boundaries.geom, :user_point)`
   * Displays honest area highlights in the user interface instead of misleading fake street pins.
3. **Tier 3 (The Remote Search)**:
   * Used for virtual, phone, or at-home volunteer opportunities.
   * Query: `WHERE shifts.location_id IS NULL`

For the architectural decision record behind this strategy, see ADR 0003: Dual-Track Geospatial Strategy.

[4326]: https://epsg.io/4326

[PostGIS Geography Type]: https://postgis.net/workshops/postgis-intro/geography.html

[ISO 3166-1 alpha-2]: https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2

[PostGIS ST_Intersects]: https://postgis.net/docs/ST_Intersects.html

[Single Table Inheritance Pattern]: https://martinfowler.com/eaaCatalog/singleTableInheritance.html

[Why OFFSET is Bad (Keyset Pagination)]: https://use-the-index-luke.com/no-offset

[discriminator]: Project_Discriminator.md

[Event Sourcing & Audit Logs]: https://microservices.io/patterns/data/event-sourcing.html

[Understanding Spatial Projections and SRID 4326]: https://postgis.net/workshops/postgis-intro/projection.html
