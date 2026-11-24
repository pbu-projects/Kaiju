# Contributing to Kaiju

Guidelines for contributing to the Kaiju backend service.

---

## 1. Standards & References

Do not rewrite or reinvent established patterns. Follow the canonical documentation:

* **Architecture & DTO Standards:** Refer to the internal Field Guide specification for guidelines on Java 25 records, reflection-free AOT compilation, and DTO boundaries.
* **Testing Guidelines:** See [Testing Guidelines](TESTING.md) and refer to the internal Field Guide specification for Spock, Testcontainers, and PostGIS boundary test rules.
* **Security & Access Controls:** Refer to [Testing Guidelines](TESTING.md) and the internal Field Guide security matrix.
* **Personas & User Journeys:** Refer to the internal Field Guide specification for canonical Personas and Key User Journeys.

---

## 2. Code Style & Naming

See [Style Guide](STYLEGUIDE.md) for naming conventions, import hygiene, SonarClean rules, and code style standards.

---

## 3. Local Testing

All tests run against live PostgreSQL + PostGIS containers managed by Testcontainers.

```bash
# Start local Authentik identity provider
docker compose -f database/compose.yml up -d authentik-server authentik-worker

# Run the test suite
./gradlew test
```

* All tests must pass locally before opening or updating a Pull Request.
* Avoid mocking when real database/service containers can be used.
* See [TESTING.md](TESTING.md) for full details on test fixtures, personas, and boundary checks.

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
