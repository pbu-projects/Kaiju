monster-platform/                 # The root repository folder
├── gradle.properties             # Shared versions (Micronaut, Kotlin, Compose)
├── settings.gradle.kts           # Maps the module boundaries
├── gradlew
├── database/                     # Docker and Spatial Infrastructure
│   ├── compose.yml               # SELinux-aware (:Z) PostGIS container
│   └── init/
│       ├── 01-schema.sql         # Core domain (organizations, locations, projects, shifts)
│       ├── 02-fencing.sql        # Spatial join layer (boundary_layers, boundaries)
│       └── 03-seed-data.sql      # Static geocoding lookups and test points
├── modules/                      # Backend Ecosystem
│   ├── core/
│   │   ├── build.gradle.kts      # Applies 'io.micronaut.library'
│   │   └── src/main/kotlin/monster/volunteer/platform/core/
│   │       ├── domain/           # Database entities with JTS spatial types (e.g., Project, Shift)
│   │       └── repository/       # Postgres/PostGIS interfaces
│   │
│   ├── sync/
│   │   ├── build.gradle.kts      # Applies 'io.micronaut.library'
│   │   └── src/main/kotlin/monster/volunteer/platform/sync/
│   │       ├── jobs/             # @Scheduled Micronaut scraping tasks
│   │       └── ingestion/        # Data normalization and zero-cost local geocoding pipelines
│   │
│   └── server/
│       ├── build.gradle.kts      # Applies 'io.micronaut.application'
│       ├── src/main/kotlin/monster/volunteer/platform/server/
│       │   ├── controllers/      # HTTP endpoints for APIs and web pages
│       │   └── auth/             # Keycloak integration logic
│       └── src/main/resources/
│           ├── application.yml   # Server configuration
│           └── views/            # JTE HTML templates for the SEO-optimized public portal
└── mobile/                       # Compose Multiplatform Applications
├── shared/                   # Shared Kotlin UI components and state logic
├── androidApp/               # Native Android application wrapper
└── iosApp/                   # Native iOS application wrapper