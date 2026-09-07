# Kaiju

Backend service for Volunteer Monster.

## Tech Stack

- **Framework**: [Micronaut 5](https://micronaut.io/) (Java 25)
- **Testing**: We test with [Spock](https://spockframework.org/) and [Testcontainers](https://www.testcontainers.org/).
- **Database**: PostgreSQL + PostGIS via [Flyway](https://flywaydb.org/) and [HikariCP](https://micronaut-projects.github.io/micronaut-sql/latest/guide/index.html#jdbc). See [Database Schema](./database/DB.md).
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

## Documentation

<details>
<summary><b>Project Guides</b></summary>

- [Architecture & UI](./docs/architecture.md)
- [Domain & Security](./docs/domain.md)
- [Testing](./docs/testing.md)
- [Database Schema](./database/DB.md)
- [Project Types](./database/Project_Discriminator.md)

</details>

- [Micronaut Documentation](https://docs.micronaut.io/latest/guide/index.html)
- [Micronaut Guides](https://guides.micronaut.io/index.html)
