monster-platform/                 # The root repository folder
├── gradle.properties             # Shared versions (Micronaut, Kotlin)
├── settings.gradle.kts           # Maps the module boundaries
├── gradlew
├── modules/
│   ├── core/
│   │   ├── build.gradle.kts      # Applies 'io.micronaut.library'
│   │   └── src/main/kotlin/monster/volunteer/platform/core/
│   │       ├── domain/           # Database entities (e.g., Project, Shift)
│   │       └── repository/       # Postgres/Cosmos DB interfaces
│   │
│   ├── sync/
│   │   ├── build.gradle.kts      # Applies 'io.micronaut.library'
│   │   └── src/main/kotlin/monster/volunteer/platform/sync/
│   │       ├── jobs/             # @Scheduled Micronaut scraping tasks
│   │       └── ingestion/        # Data normalization and geocoding pipelines
│   │
│   └── server/
│       ├── build.gradle.kts      # Applies 'io.micronaut.application'
│       ├── src/main/kotlin/monster/volunteer/platform/server/
│       │   ├── controllers/      # HTTP endpoints
│       │   └── auth/             # Keycloak integration logic
│       └── src/main/resources/
│           ├── application.yml   # Server configuration
│           └── views/            # Your JTE HTML templates
