# Contributing to Kaiju

Guidelines for contributing to the Kaiju backend service.

---

## 1. Standards & References

Follow these guides instead of inventing new patterns:

* **Code Style, Naming & DTOs:** See [Style Guide](STYLEGUIDE.md) for naming rules, DTO boundaries, import hygiene, and SonarClean quality standards.
* **Testing, Security & Personas:** See [Testing Guidelines](TESTING.md) for Spock and Testcontainers conventions, PostGIS boundary checks, access control rules, and canonical personas.

---

## 2. Local Testing

All tests must pass locally before opening or updating a Pull Request. See [Testing Guidelines](TESTING.md#6-running-tests-locally) for setup steps and `./gradlew test` commands.

---

## 3. Pull Request Expectations

Pull requests must follow [`.github/pull_request_template.md`](.github/pull_request_template.md) and include:

1. **Why the Change is Proposed:** State the problem, goal, or issue being solved.
2. **File-by-File Breakdown:** Explain *why* each file changed and *what* changed.
3. **Test Validation (Journeys & Personas):** Describe the new or updated tests using the personas and user journeys in [Testing Guidelines](TESTING.md). Include negative authorization checks (`401`/`403`) and client context where needed.

---

## 4. Git Workflow

* Stage exact files explicitly with `git add <file>`. Do not use `git commit -a` or `git commit -am`.
* Ensure `./gradlew test` passes locally before committing.
* Link relevant GitHub issues in the PR description (e.g., `Fixes #12` or `Refs #59`).
