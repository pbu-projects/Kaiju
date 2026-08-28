# Kaiju

Backend service for Volunteer Monster.

## Tech Stack

- **Framework**: [Micronaut 5](https://micronaut.io/) (Java 25)
- **Testing**: We test with [Spock](https://spockframework.org/) and [Testcontainers](https://www.testcontainers.org/).
- **Database**: PostgreSQL + PostGIS via [Flyway](https://flywaydb.org/) and [HikariCP](https://micronaut-projects.github.io/micronaut-sql/latest/guide/index.html#jdbc). See [Database Architecture](./database/DB.md).
- **Security**: [Micronaut Security](https://micronaut-projects.github.io/micronaut-security/latest/guide/index.html) JWT authentication via [authentik](https://goauthentik.io/).
- **Serialization & Validation**: [Jackson Serde](https://micronaut-projects.github.io/micronaut-serialization/latest/guide/) and [Jakarta Validation](https://micronaut-projects.github.io/micronaut-validation/latest/guide/).
- **Build & Optimization**: Gradle with [Micronaut AOT](https://micronaut-projects.github.io/micronaut-aot/latest/guide/) and [GraalVM](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html).

## Quick Start

```bash
# Run application
./gradlew run

# Run tests
./gradlew test

# Build native executable with GraalVM
./gradlew nativeCompile
```

## Resources

- [Database Architecture](./database/DB.md)
- [Project Discriminator Strategy](./database/Project_Discriminator.md)
- [Micronaut Documentation](https://docs.micronaut.io/5.0.5/guide/index.html)
- [Micronaut Guides](https://guides.micronaut.io/index.html)
