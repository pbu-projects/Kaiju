# Kaiju

Backend service for Volunteer Monster—a modern platform connecting volunteers with community initiatives.

## Project Status

> [!NOTE]
> **Work in Progress**: Kaiju is currently under active foundational development. Standalone execution guides and quick-start instructions will be added as deployment and service configurations stabilize.

## Why Volunteer Monster?

Nonprofits shouldn't have to choose between clunky legacy software and costly enterprise platforms. Kaiju is built to deliver real-world impact:

<details>
<summary><b>Hyper-Local Discovery</b>: Volunteers find initiatives right in their neighborhood without zip code guesswork.</summary>

- **How it works**: Uses PostgreSQL + PostGIS with native spatial geography indexing (`GEOGRAPHY(Point, 4326)`). Proximity searches and regional boundary intersections (`ST_DWithin`, `ST_Intersects`) are processed directly within the database engine, avoiding slow, imprecise centroid approximations.

</details>

<details>
<summary><b>Budget-Friendly Frugality</b>: Minimal server and memory overhead keeps hosting costs near zero.</summary>

- **How it works**: Built with Micronaut 5 compile-time dependency injection and Micronaut AOT, eliminating heavy runtime reflection and dynamic proxying. It runs with an ultra-lean memory footprint (or as a native GraalVM binary), allowing nonprofits to host on low-cost compute tiers.

</details>

<details>
<summary><b>Trusted & Safe</b>: Multi-layer verification, moderation queues, and immutable audit logs keep communities safe.</summary>

- **How it works**: Enforces multi-tier Role-Based Access Control (RBAC) across platform, regional, and organizational scopes. New projects enter PostGIS-routed regional moderation queues with escalation timers, while sensitive state changes record tamper-evident audit logs within the same database transaction.

</details>

<details>
<summary><b>Fast & Resilient</b>: Sub-millisecond response times sustain major community drives and disaster mobilizations.</summary>

- **How it works**: Runs on Java 25 with Generational ZGC for consistent sub-millisecond garbage collection pauses under heavy request loads. A unified relational model enables instant cross-domain joins across projects, shifts, and signups in single queries without distributed transaction overhead.

</details>

## Code Quality & Tech Stack

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=PeanutButter-Unicorn_kaiju&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=PeanutButter-Unicorn_kaiju)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=PeanutButter-Unicorn_kaiju&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=PeanutButter-Unicorn_kaiju)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=PeanutButter-Unicorn_kaiju&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=PeanutButter-Unicorn_kaiju)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=PeanutButter-Unicorn_kaiju&metric=coverage)](https://sonarcloud.io/summary/new_code?id=PeanutButter-Unicorn_kaiju)

- **Framework**: [Micronaut 5](https://micronaut.io/) (Java 25)
- **Testing**: [Spock](https://spockframework.org/) and [Testcontainers](https://www.testcontainers.org/) with live PostgreSQL/PostGIS containers.
- **Database**: PostgreSQL + PostGIS via [Flyway](https://flywaydb.org/) and [HikariCP](https://micronaut-projects.github.io/micronaut-sql/latest/guide/index.html#jdbc). See [Database Schema](./database/DB.md).
- **Security**: [Micronaut Security](https://micronaut-projects.github.io/micronaut-security/latest/guide/index.html) JWT authentication via [authentik](https://goauthentik.io/).
- **Serialization & Validation**: Java 25 Records via [Micronaut Serialization](https://micronaut-projects.github.io/micronaut-serialization/latest/guide/) (compile-time, reflection-free) and [Jakarta Validation](https://micronaut-projects.github.io/micronaut-validation/latest/guide/).
- **Build & Optimization**: Gradle with [Micronaut AOT](https://micronaut-projects.github.io/micronaut-aot/latest/guide/) and [GraalVM](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html).
- **Want to Help?** See [Contributing](./CONTRIBUTING.md).
