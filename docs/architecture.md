# Architecture & UI

Overview of application structure, frontend rendering, and compilation standards.

## Application Structure

Kaiju is a single-module Micronaut application under `lol.pbu.kaiju.*`:

- **`controller`**: HTTP REST endpoints and web routing. Endpoints return DTOs/view models rather than raw database entities.
- **`domain`**: Database entities, JTS spatial types (`Point`, `Polygon`), and lifecycle converters.
- **`model`**: DTOs, request/response models, and enums.
- **`repository`**: Micronaut Data JDBC repositories and custom spatial SQL queries.

## Frontend Strategy

- **Public Web**: [JTE (Java Template Engine)](https://jte.gg/) compiles server-side templates directly to Java bytecode for fast response times and clean HTML SEO for search crawlers.
- **Admin Dashboards & Mobile**: [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) handles rich, interactive state in logged-in portals and provides shared UI logic across desktop and mobile clients.

## GraalVM & Native Compilation

The application is built to compile ahead-of-time with GraalVM Native Image:

- Rely on compile-time dependency injection (`@Singleton`, `@Inject`, `@Controller`).
- Avoid reflection and dynamic proxying at runtime.
- Use immutable Java `record`s or immutable models for DTOs.
- Keep dependencies reflection-free and lightweight.
