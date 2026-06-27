# Agent Context: Architecture & UI Strategy
**Domain**: Web Boundaries, JTE Views, Jetpack Compose, Module Isolation, GraalVM AOT
**Context**: This file governs all AI-generated code regarding module separation, UI component generation, HTTP controllers, and compilation constraints for the Volunteer Monster platform.

## 1. Strict Module Boundaries
Volunteer Monster relies on a decoupled monorepo to protect web performance from backend heavy-lifting.

- **The `sync` Module (ETL/Scraping):** This module handles the automated enhancement and ingestion of third-party data (e.g., UServeUtah, Samaritan Scout).
    - **AI Directive:** NEVER import or call `sync` services directly from the `server` module's HTTP controllers. The sync engine runs asynchronously on independent cron jobs or completely separate container instances.
- **The `server` Module:** This is the high-performance public-facing application. It reads and writes data via the `core` module but does not know or care how the external scraped data got into the database.

---

## 2. The Hybrid UI Strategy
The platform uses a two-pronged frontend approach to balance public SEO requirements with complex, reactive administrative state.

### A. JTE (Java Template Engine) for the Public Web
- **Rule:** All public-facing pages (e.g., spatial project searches, landing pages, public organization profiles) MUST be built using JTE.
- **Why:** JTE compiles templates directly to Java bytecode. It guarantees instantaneous page loads, eliminates JavaScript bloat, and provides perfect HTML semantic SEO for web crawlers (like JustServe's search index).
- **AI Directive:** When asked to create a public view, generate a `.jte` file. Rely on server-side rendering, standard HTML forms, and native browser behavior over Single Page Application (SPA) API calls.

### B. Compose Multiplatform for the Admin Portal
- **Rule:** The authenticated Admin Portal (where organizations manage shifts, approve projects, and view analytics) is reserved for JetBrains Compose.
- **Why:** Admin tools require complex, reactive state management where SEO is irrelevant. Using Compose ensures that the UI components can eventually be shared natively across iOS and Android apps for on-the-ground volunteer check-ins.

---

## 3. GraalVM & AOT Compilation Strictness (The Rocher Ethos)
The platform is strictly designed to compile to a GraalVM Native Image to achieve near-zero cold start times and minimal memory footprints.

- **Rule:** Code must be completely reflection-free.
- **AI Directive:** - ALWAYS utilize Micronaut's compile-time dependency injection (`@Singleton`, `@Client`, etc.).
    - NEVER use runtime dynamic proxying (`java.lang.reflect`).
    - AVOID importing heavy, legacy Java libraries that rely on dynamic classloading.
    - ALWAYS use Java `record` classes for immutable DTOs and Data Transfer Objects to ensure thread safety and low garbage-collection overhead.

---

## 4. API & Controller Standards
- **Rule:** Controllers must act as a strict shield for the database.
- **AI Directive:** HTTP endpoints in `@Controller` classes (e.g., `ProjectController`) must never return raw database entities (like `Project` or `ShiftRecord`) to the view layer. You must always map database entities to specific response DTOs (like `ProjectCardView`) to prevent leaking internal PostGIS column names or triggering massive lazy-loading memory traps.