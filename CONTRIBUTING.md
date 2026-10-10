# Contributing to Kaiju

Guidelines for contributing to the Kaiju backend service.

---

## 1. Standards & References

Do not rewrite or reinvent established patterns. Follow the canonical documentation:

* **Architecture & DTO Standards:** Refer to the internal Field Guide specification for guidelines on Java 25 records, reflection-free AOT compilation, and DTO boundaries.
* **Testing Guidelines:** Refer to the internal Field Guide specification for Spock, Testcontainers, and PostGIS boundary test rules.
* **Security & Access Controls:** Refer to the [Security Test Plan](test-plan.md) and the internal Field Guide security matrix.
* **Personas & User Journeys:** Refer to the internal Field Guide specification for canonical Personas and Key User Journeys.

---

## 2. Code Style & Import Standards

We follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) with strict enforcement of import hygiene and clean code practices:

* **Explicit Imports (Google Java Style §3.3):**
  - **No Wildcard Imports:** Wildcard imports (`import foo.bar.*`) are strictly prohibited (Google Java Style §3.3.1).
  - **No Inline FQDNs / FQCNs:** **NEVER** use fully qualified domain/class names (e.g., `java.util.List`, `jakarta.validation.constraints.NotNull`, `org.slf4j.Logger`) inline within Java or Groovy code bodies, method signatures, return types, field definitions, or annotations.
  - **Always Declare Explicit Imports:** Always declare appropriate, explicit `import` statements at the top of the file.
  - **Name Collisions Exception:** The *only* exception to inline FQCN usage is resolving an unavoidable simple-name collision between two imported types in the same file (e.g., `java.util.Date` vs `java.sql.Date`).
* **Quality & Reliability Standards (SonarClean):**
  - **Thread-Safety & Lifecycle:** Avoid unmanaged `ThreadLocal` allocations in singleton beans (Sonar rule `java:S5164`). Prefer lightweight direct instantiation or managed container lifecycles.
  - **Control Flow:** Avoid nested ternary operators (Sonar rule `java:S3358`) and collapsible nested `if` statements (Sonar rule `java:S1066`).
  - **Null Safety:** Return empty collections or empty arrays rather than `null` from query and builder methods.

---

## 3. Local Testing

All tests run against live PostgreSQL + PostGIS containers managed by Testcontainers.

```bash
./gradlew test
```

* All tests must pass locally before opening or updating a Pull Request.
* Avoid mocking when real database/service containers can be used.

---

## 4. Pull Request Expectations

Pull requests must follow [`.github/pull_request_template.md`](.github/pull_request_template.md) and include:

1. **Why the Change is Proposed:** State the overarching problem, goal, or issue being resolved.
2. **File-by-File Breakdown:** Explicitly explain *why* each file was changed and *what* changed.
3. **Test Validation (Journeys & Personas):** Detail the new or updated tests validating the change, framed against the relevant project Personas and User Journeys (from internal Field Guide specifications). Include negative authorization checks (`401`/`403`) and client context where applicable.

---

## 5. Git Workflow

* Stage exact files explicitly with `git add <file>`. Do not use `git commit -a` or `git commit -am`.
* Ensure `./gradlew test` passes locally before committing.
* Link relevant GitHub issues in the PR description (e.g., `Fixes #12` or `Refs #59`).
