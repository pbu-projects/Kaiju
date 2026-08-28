# Volunteer Monster

Volunteer Monster connects community organizers with volunteers to get things done.

## Design Priorities

- **No Bloat**: Simple, focused deliverables without waterfall-in-sprints.
- **No Bandwagon**: Built on a solid [Java](https://dev.java/) foundation.
- **User Focus**: Prioritizing verified user journeys and passing tests over buzzwords.

## Tech Stack & Architecture

- **Framework**: [Micronaut 5](https://micronaut.io/) with [GraalVM](https://www.graalvm.org/) for fast native compilation.
- **Location & Database**: PostgreSQL + [PostGIS](https://postgis.net/) managed via [Flyway](https://flywaydb.org/) and pooled with [HikariCP](https://micronaut-projects.github.io/micronaut-sql/latest/guide/index.html#jdbc). See [Database Architecture](database/DB.md) and [Project Discriminator Strategy](database/Project_Descriminator.md).
- **Frontend**: [JTE](https://jte.gg/) for server-rendered HTML and [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) for multiplatform dashboards.
- **Security**: [Micronaut Security](https://micronaut-projects.github.io/micronaut-security/latest/guide/index.html) JWT authentication via [authentik](https://goauthentik.io/).
- **Testing**: We test with [Spock](https://spockframework.org/) and [Testcontainers](https://testcontainers.com/).

## Quick Start

```bash
# Run application
./gradlew run

# Run tests
./gradlew test

# Build executable fat JAR
./gradlew shadowJar
```

## Resources

- [Database Architecture](database/DB.md)
- [Project Discriminator Strategy](database/Project_Descriminator.md)
- [Micronaut Documentation](https://docs.micronaut.io/5.0.5/guide/index.html)
- [Micronaut Guides](https://guides.micronaut.io/index.html)

