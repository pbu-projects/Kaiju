# Agent Context: Volunteer Monster Root Manifest
**System Goal:** This is the root instruction file for the Volunteer Monster monorepo. AI agents must read this file to understand the global system architecture, core development philosophy, and where to find domain-specific rules.

## 1. Project Overview & Architecture
Volunteer Monster is a globally scalable, highly performant volunteer management platform. It handles hyper-local spatial search, rigorous M&A-grade audit trails, and automated ETL data ingestion.

The application is built on a **multi-module Gradle architecture** using **Micronaut**:
- `core`: Contains the domain objects, database entities, and shared data logic.
- `server`: The primary backend server, housing the HTTP controllers, the Authentik IAM integration, and the high-performance JTE (Java Template Engine) frontend.
- `sync`: The independent ingestion engine for harvesting and enhancing data from external aggregators (e.g., UServeUtah, Samaritan Scout). **Must remain strictly decoupled from the web server.**

## 2. Core Developer Priorities (The "Graeme Rocher" Ethos)
All generated code must adhere to the following non-negotiable principles:

1. **AOT & GraalVM Native Ready:** - Write reflection-free, RAM-efficient code.
    - Avoid runtime dynamic proxying, heavy runtime classloading, and bloated Java libraries.
    - Utilize Micronaut's ahead-of-time (AOT) compilation and compile-time metadata generation.
2. **Surgical, Behavior-Driven Testing:**
    - Focus on User Journey coverage, boundary conditions, and regional data anomalies (e.g., missing zip codes) over arbitrary code-coverage metrics.
    - Use Data-Driven Spock specifications (`@Unroll` and `where:` blocks).
3. **Explicit Observability:**
    - Error messages and validation constraints must be explicitly descriptive.
    - *Example:* Use `"Organization Name must be between 2 and 100 characters"` instead of `"name must not be blank"`.
4. **Self-Documenting & Clean:**
    - Rely on highly descriptive, full-length variable and method names rather than inline comments.
    - Provide structured Javadocs for interfaces and public methods.

## 3. Infrastructure & Environment
- **Database:** PostgreSQL with PostGIS extension. Spun up locally via Docker Compose in the `/database` directory.
- **Identity & Access Management (IAM):** Authentik (Hybrid enforcement approach where Authentik issues the JWT, and Micronaut enforces role-based access).

---

## 4. AI Agent Context Router
To preserve token limits and prevent hallucination, domain-specific rules have been modularized. **AI Agents: Do not guess business logic.** If you are working on a specific domain, you must read the corresponding `.agents/` file before generating code.

- 🖥️ **Working on the Web UI, JTE views, or module boundaries?**
  👉 Read `@01-architecture-ui.md`

- 🐘 **Writing SQL migrations, PostGIS queries, or Micronaut Data entities?**
  👉 Read `@02-database-postgis.md`

- 🧪 **Writing unit/integration tests or database cleanup logic?**
  👉 Read `@03-testing-spock.md`

- 🏢 **Handling organization workspaces, RBAC endpoints, or Sync/ETL logic?**
  👉 Read `@04-domain-business.md`