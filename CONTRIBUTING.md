# Contributing to Kaiju

Guidelines for contributing to the Kaiju backend service.

---

## 1. Standards & References

Do not rewrite or reinvent established patterns. Follow the canonical documentation:

* **Architecture & DTO Standards:** Refer to [Architecture & UI](docs/03-architecture.adoc) for guidelines on Java 25 records, reflection-free AOT compilation, and DTO boundaries.
* **Testing Guidelines:** Refer to [Testing Strategy](docs/04-testing-strategy.adoc) for Spock, Testcontainers, and PostGIS boundary test rules.
* **Security & Access Controls:** Refer to the [Security Test Plan](test-plan.md) and [Security Matrix](docs/06-security-matrix.adoc).
* **Personas & User Journeys:** Refer to the internal Field Guide specification for canonical Personas and Key User Journeys.

---

## 2. Local Testing

All tests run against live PostgreSQL + PostGIS containers managed by Testcontainers.

```bash
./gradlew test
```

* All tests must pass locally before opening or updating a Pull Request.
* Avoid mocking when real database/service containers can be used.

---

## 3. Pull Request Expectations

Pull requests must follow [`.github/pull_request_template.md`](.github/pull_request_template.md) and include:

1. **Why the Change is Proposed:** State the overarching problem, goal, or issue being resolved.
2. **File-by-File Breakdown:** Explicitly explain *why* each file was changed and *what* changed.
3. **Test Validation (Journeys & Personas):** Detail the new or updated tests validating the change, framed against the relevant project Personas and User Journeys (from internal Field Guide specifications). Include negative authorization checks (`401`/`403`) and client context where applicable.

---

## 4. Git Workflow

* Stage exact files explicitly with `git add <file>`. Do not use `git commit -a` or `git commit -am`.
* Ensure `./gradlew test` passes locally before committing.
* Link relevant GitHub issues in the PR description (e.g., `Fixes #12` or `Refs #59`).
