# Architecture & UI

Overview of application structure, frontend rendering, and compilation standards.

## Application Structure

Kaiju is a single-module Micronaut application under `lol.pbu.kaiju.*`:

- **`controller`**: HTTP REST endpoints and web routing. Endpoints return DTOs/view models rather than raw database entities.
- **`domain`**: Database entities, JTS spatial types (`Point`, `Polygon`), and lifecycle converters.
- **`model`**: DTOs, request/response models, and enums.
- **`repository`**: Micronaut Data JDBC repositories and custom spatial SQL queries.

## Frontend Strategy

- **Public Web**: [JTE (Java Template Engine)](https://jte.gg/) compiles server-side templates directly to Java bytecode for fast response times. This provides a massive benefit for **SEO ranking** by serving clean, pre-rendered HTML to search crawlers.
- **Admin Dashboards & Mobile**: [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) handles rich, interactive state in logged-in portals. By sharing UI logic across desktop, web, and mobile clients, it **saves significant dev time** and provides a **consistent experience between devices for admins**.

## Backend Strategy

The backend uses Micronaut to provide a fast, reflection-free dependency injection framework, giving us the flexibility to deploy services using either GraalVM Native Image or a standard HotSpot JVM based on the workload.

To maintain compatibility across both deployment targets:
- Rely on compile-time dependency injection (`@Singleton`, `@Inject`, `@Controller`).
- Avoid reflection and dynamic proxying at runtime.
- Use immutable Java `record`s or immutable models for DTOs.
- Keep dependencies reflection-free and lightweight.

### Deployment Targets

**1. GraalVM Native Image (Community Edition)**
Best suited for **short-lived, low GC-heavy work** such as background worker tasks or short-lived event handlers. Its instant startup and minimal memory footprint are ideal for services that need to scale rapidly or handle bursty, short-lived traffic.

**2. HotSpot JVM (Eclipse Temurin Java 25 + Generational ZGC)**
Best suited for the **core public API and spatial search services**. Processing large payloads from PostgreSQL spatial queries into JSON generates a massive amount of short-lived objects. HotSpot is the target for these services because:
- **Low Latency:** Generational ZGC cleans up heavy JSON/DTO allocation loads with sub-millisecond pause times, preventing latency spikes.
- **Peak Throughput:** The C2 JIT compiler actively optimizes long-running threads based on real-time traffic patterns, maximizing requests-per-second.

*Note: If the project considers upgrading to Oracle GraalVM (the non-community version), this strategy ought to be reconsidered. Oracle GraalVM includes the G1 Garbage Collector and Profile-Guided Optimizations (PGO), which significantly narrows the throughput and latency gap for long-running stateful services.*

## Database Strategy & Spatial Math

The architecture utilizes a single PostgreSQL database equipped with the PostGIS extension.

### Benefits of a Single Database

Rather than splitting data across isolated databases for each service, all services connect to a single PostgreSQL instance. This provides several key benefits:
- **Instant Cross-Domain Joins:** Services can natively join operational data (e.g., projects and shifts) directly to geographic locations in a single SQL query, avoiding slow and complex network-level joins.
- **Data Integrity:** Strict foreign key constraints guarantee data consistency across different business domains without the need for distributed transactions.
- **Operational Simplicity:** Managing a single, robust PostgreSQL database drastically reduces infrastructure overhead, backup complexity, and connection management.

### Spatial Queries vs. Spatial Math

It is important to distinguish where the spatial workload is processed:
- **Geospatial Queries (JVM):** The Micronaut JVM orchestrates the data retrieval. It constructs the SQL queries, executes them against the database, and maps the resulting coordinate data into DTOs and JSON.
- **Geospatial Math (Database):** **All** heavy mathematical spatial computation (e.g., distance calculations, radius filtering via `ST_DWithin`, bounding box intersections) is pushed down to the database. Rather than reinventing the wheel in Java, we lean into PostgreSQL and PostGIS—an already highly performant, C-based technology—which handles these computations efficiently using GiST indexes. This ensures the JVM never has to load unfiltered raw coordinates into memory to do math.
