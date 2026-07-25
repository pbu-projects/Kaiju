kaiju/                            # The root repository folder
├── build.gradle.kts              # Root build configuration
├── settings.gradle.kts           # Root settings (single module)
├── gradle.properties             # Project versions
├── gradlew                       # Gradle wrapper script
├── database/                     # Docker and Spatial Infrastructure
│   ├── compose.yml               # SELinux-aware (:Z) PostGIS container
│   └── init/
│       ├── 01-schema.sql         # Core domain (organizations, locations, projects, shifts)
│       └── ...                   # Data initialization scripts
└── src/                          # Single Module Source Directory
    ├── main/
    │   ├── java/lol/pbu/
    │   │   ├── Application.java  # Main application entry point
    │   │   └── kaiju/
    │   │       ├── controller/   # REST Controllers & HTTP endpoints
    │   │       ├── domain/       # Database entities & JTS spatial types
    │   │       ├── model/        # Data models & converters
    │   │       └── repository/   # Data access repositories
    │   └── resources/
    │       ├── application.yml   # Application configuration
    │       ├── logback.xml       # Logging configuration
    │       └── views/            # HTML views / templates
    └── test/
        ├── groovy/lol/pbu/       # Spock integration & unit tests
        └── resources/
            └── application-test.yml # Test environment configuration