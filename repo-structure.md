# Repository Structure

```text
kaiju/                            # The root repository folder
├── build.gradle.kts              # Root build configuration
├── settings.gradle.kts           # Root settings (single module)
├── gradle.properties             # Project versions
├── gradlew                       # Gradle wrapper script
├── bin/                          # Helper binaries / run scripts
├── scripts/                      # Developer utility scripts
├── database/                     # Docker and Spatial Infrastructure
│   ├── compose.yml               # SELinux-aware (:Z) PostGIS container
│   ├── volunteer_monster - public.erd      # ERD source file
│   ├── volunteer_monster - public.gif      # ERD diagram (GIF)
│   ├── volunteer_monster - public.graphml  # ERD diagram (GraphML)
│   ├── volunteer_monster - volunteer_monster - public.png  # ERD diagram (PNG)
│   └── init/
│       ├── 01-schema.sql         # Core domain (organizations, locations, projects, shifts)
│       ├── 02-users.sql          # Seed users
│       ├── 03-organizations.sql  # Seed organizations
│       ├── 04-locations.sql      # Seed locations
│       ├── 05-projects.sql       # Seed projects
│       ├── 06-project-locations.sql  # Seed project-location associations
│       ├── 07-shifts.sql         # Seed shifts
│       └── 08-global-locations.sql   # Seed global location reference data
└── src/                          # Single Module Source Directory
    ├── main/
    │   ├── java/lol/pbu/
    │   │   ├── Application.java  # Main application entry point
    │   │   └── kaiju/
    │   │       ├── controller/   # REST Controllers & HTTP endpoints
    │   │       ├── domain/       # Database entities & JTS spatial types
    │   │       ├── model/        # DTOs, enums & JTS type converters
    │   │       └── repository/   # Data access repositories
    │   └── resources/
    │       ├── application.yml   # Application configuration
    │       ├── logback.xml       # Logging configuration
    │       └── views/            # HTML views / templates
    └── test/
        ├── groovy/lol/pbu/       # Spock integration & unit tests
        └── resources/
            └── application-test.yml # Test environment configuration
```
