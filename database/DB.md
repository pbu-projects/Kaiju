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

- Uses a `project_type` discriminator (`STANDARD`, `OPEN_DOOR`, `REGIONAL_ROSTER`, `REGIONAL_BILLBOARD`) to dictate UI
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
controls over scraped data.

- An append-only ledger tracking every major action (Created, Approved, Rejected, Edited). Ensures the moderators can
  find data somewhere if it's eventually needed
- Reading: [Event Sourcing & Audit Logs]

## Key Queries

- The Local Radius Search: `ST_DWithin(locations.geom, :user_point, :radius)`
- The Regional Boundary Match: `ST_Intersects(boundaries.geom, :user_point)`
- The Remote Search: `WHERE shifts.location_id IS NULL`

[4326]: https://gis.stackexchange.com/questions/3334/difference-between-wgs84-and-epsg4326

[PostGIS Geography Type]: https://postgis.net/workshops/postgis-intro/geography.html

[ISO 3166-1 alpha-2]: https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2

[PostGIS ST_Intersects]: https://postgis.net/docs/ST_Intersects.html

[Single Table Inheritance Pattern]: https://martinfowler.com/eaaCatalog/singleTableInheritance.html

[Why OFFSET is Bad (Keyset Pagination)]: https://use-the-index-luke.com/no-offset

[Event Sourcing & Audit Logs]: https://microservices.io/patterns/data/event-sourcing.html

[Understanding Spatial Projections and SRID 4326]: https://postgis.net/workshops/postgis-intro/projection.html

[EPSG 4326 vs 3857 in Web Mapping]: https://lyzidiamond.com/posts/4326-vs-3857