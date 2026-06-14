# The Project Discriminator Strategy

In the `projects` table, we use the `project_type` column as a discriminator field. This is an implementation of the Single Table Inheritance (STI) pattern.

If we split these variations into four separate tables, the search engine would have to perform massive, slow `UNION` operations every time a user searched for "opportunities near me." By keeping them in a single table, the PostGIS spatial index can scan the entire ecosystem in milliseconds. The frontend (JTE templates) then uses this discriminator to radically alter how the project card is rendered to the user.

Here is the architectural breakdown of why each type exists and how the system handles it.

### 1. STANDARD
The traditional, highly-structured volunteer opportunity.
* Concept: A specific task happening at a specific time (e.g., Saturday Morning Soup Kitchen).
* Spatial Rule: Must link to exact coordinate Points via the `project_locations` bridge table.
* Execution Rule: Heavily utilizes the `shifts` table to manage capacity, waitlists, and start/end times.
* UI Rendering: Displays exact driving distance (e.g., "3.2 miles away"). Renders a calendar picker and a strict "Sign Up" button.

### 2. OPEN_DOOR
A flexible opportunity with a location, but no strict time slots.
* Concept: Walk-in volunteering or donation drop-offs (e.g., "Drop off winter coats any time between 9 AM and 5 PM, Monday-Friday").
* Spatial Rule: Must link to exact coordinate Points via the `project_locations` bridge table.
* Execution Rule: Bypasses the `shifts` table completely. Relies on the project's description or operating hours.
* UI Rendering: Displays exact driving distance. Hides the calendar and shift selection entirely. Replaces "Sign Up" with "Get Directions" or "View Operating Hours."

### 3. REGIONAL
distributed work with no specific location. this is NOT for projects with multiple locations. 
* Concept: Work that happens at the volunteer's home or anywhere within a jurisdiction (e.g., Fostering a rescue dog, or joining a Disaster Response waitlist).
* Spatial Rule: Bypasses Points completely. Uses Polygons via the `project_boundaries` bridge table to enforce geofencing (e.g., "Must reside in Davis County").
* Execution Rule: Volunteers are added to a project-level waitlist or roster rather than signing up for a specific hourly shift.
* UI Rendering: Hides all distance metrics (calculating distance to a foster dog makes no sense). Displays a "Region-Wide Opportunity" badge. Replaces the shift picker with a "Join Roster" button.

---

[Single Table Inheritance Pattern]: https://martinfowler.com/eaaCatalog/singleTableInheritance.html