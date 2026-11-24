# Kaiju Style Guide

Where this guide does not state a specific decision, follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).

---

## 1. Naming Conventions

Name classes, methods, and files so anyone can tell what they do without opening them.

* **No `Impl` suffixes:**
  * Use `Default<Name>` when an interface is needed (for example, `DefaultUserService`, `DefaultShiftService`).
  * When only one implementation exists and no interface is needed, use a single concrete `@Singleton` class (for example, `AuthorizationService`, `BoundaryIngestionService`).
* **No `Dto`, `Utils`, or `Helper` suffixes:**
  * Name read models and value records after what they represent (`Coordinates`, `CoveringRegion`).
  * Name write payloads `<Action><Entity>Command` and place them in `lol.pbu.kaiju.dto` (for example, `CreateUserCommand`, `UpdateUserRoleCommand`). Do not put request payloads in `model/` or name them `*Request`.
  * Name utility classes after what they provide (`PageRequests` instead of `PageableUtils`).
* **Spell out non-obvious abbreviations:**
  * Prefer `IdentityProviderRoleMapper` over `IdpRoleMapper`.
  * Standard, well-known abbreviations are fine when they are clearer (for example, `UsSyncResult`).
* **Rename when scope grows:**
  * If a class outgrows its original feature, rename it to match its full job (for example, `AuthorizationService` instead of `ProjectSecurityService`, `PermissionQueryRepository` instead of `SecurityQueryRepository`).
* **Plainspoken identifiers and comments:**
  * Avoid wordy academic buzzwords in class, method, variable, and test names.
  * Skip comments when the code is clear on its own. When a comment is needed to explain *why*, keep it brief and direct, and never use em dashes.

---

## 2. Import Standards

* **No Wildcard Imports:** Wildcard imports (`import foo.bar.*`) are not allowed (Google Java Style §3.3.1).
* **No Inline FQDNs / FQCNs:** Never write fully qualified class names (such as `java.util.List`, `jakarta.validation.constraints.NotNull`, or `org.slf4j.Logger`) inline inside Java or Groovy code bodies, method signatures, return types, fields, or annotations.
* **Always Declare Explicit Imports:** Declare explicit `import` statements at the top of every file.
* **Name Collisions Exception:** Only use a fully qualified name inline when two imported types in the same file have the same simple name (such as `java.util.Date` vs `java.sql.Date`).

---

## 3. Quality & Reliability Standards (SonarClean)

* **Functional Idioms:** Prefer Java Streams and method references over imperative loops when transforming, filtering, or aggregating collections.
* **Thread-Safety & Lifecycle:** Avoid unmanaged `ThreadLocal` allocations in singleton beans (Sonar rule `java:S5164`). Prefer lightweight direct instantiation or managed container lifecycles.
* **Control Flow:** Avoid nested ternary operators (Sonar rule `java:S3358`) and collapsible nested `if` statements (Sonar rule `java:S1066`).
* **Null Safety:** Return empty collections or empty arrays rather than `null` from query and builder methods.

---

## 4. Testing Conventions

* **Assertions:** Use Spock `verifyAll` blocks when checking multiple fields or conditions on an object or response. Never write loose consecutive assertions on the same target.
* **Real Containers Over Mocks:** Prefer full HTTP pipeline integration tests against live PostgreSQL + PostGIS Testcontainers over mocking repositories or services.
* **Never Fix Failing Tests by Weakening Them:** Fix the underlying code bug rather than deleting or relaxing a failing test, unless requirements explicitly changed.
* **Fixtures & Journeys:** See [Testing Guidelines](TESTING.md) for data builders, personas, and spatial test rules.
