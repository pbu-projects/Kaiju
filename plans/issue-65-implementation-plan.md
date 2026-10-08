# Technical Implementation Plan: Issue #65 — Release 0.0.5: Transactional Services & Concurrency

## 1. Issue Overview and Scope

Release 0.0.5 represents the core architectural maturation of the Kaiju platform. This milestone transitions data access, business orchestration, and concurrency management from rapid-prototype patterns into an enterprise-grade, high-throughput, clean architecture. It eliminates time-of-check to time-of-use (TOCTOU) concurrency vulnerabilities, establishes transactional boundaries across domain aggregates, migrates execution from legacy operating system thread pools to Java 25 Virtual Threads, hardens identity provisioning with precise PostgreSQL state inspection, secures audit trails as immutable append-only logs, migrates spatial managing region validations into transactional application services, and aligns Spock test execution with connection pool visibility and mandatory database cleanup.

### Audited Issues in Release 0.0.5

* **Issue #40: Data Access: Eliminate TOCTOU 2-Query Anti-Pattern Across Controllers**: Eliminate redundant `existsById()` checks preceding `deleteById()` operations across all controllers. Replace two-round-trip queries with atomic single-query deletions returning the affected row count (`long deletedCount = repo.removeById(id); if (deletedCount == 0) throw new HttpStatusException(NOT_FOUND, ...);`). Delete `ControllerUtils.java` entirely from the codebase.
* **Issue #54: Architecture: Introduce Declarative `@Transactional` Application Service Layer**: Introduce `lol.pbu.kaiju.service` containing application services (`ProjectService`, `ShiftService`, `OrganizationService`, `UserService`, `AuditService`) annotated with declarative `@Transactional` boundaries to encapsulate multi-entity business mutations, boundary validations, and audit record generation within atomic transactions.
* **Issue #27 Migration: Spatial Managing Region Validation within `ProjectService`**: Migrate civic boundary containment and regional authority validation from controllers into `ProjectServiceImpl`. When `managingRegion` is specified during creation or update, enforce region existence, PostGIS spatial boundary containment via `ProjectSecurityService.areAllLocationsInRegion`, and caller assignment authority via `ProjectSecurityService.canAssignManagingRegion`.
* **Issue #56: Concurrency: Migrate to Java 25 Virtual Threads and Eliminate Reactor**: Transition all controllers and authentication mappers from legacy thread pools (`@ExecuteOn(TaskExecutors.BLOCKING)` and `@ExecuteOn(TaskExecutors.IO)`) to Java 25 Virtual Threads (`@ExecuteOn(TaskExecutors.VIRTUAL)`). Streamline asynchronous token mapping in `AuthentikAuthenticationMapper` by replacing Project Reactor's `Mono.fromCallable` with virtual-thread-native execution and `Publishers.just`. Eliminate the `io.micronaut.reactor:micronaut-reactor-http-client` dependency from `build.gradle.kts`.
* **Issue #23: Naive Exception Masking in `AuthentikAuthenticationMapper`**: Inspect PostgreSQL `SQLException` / `PSQLException` SQLState codes (specifically `23505` for `unique_violation` on `users.email`) instead of catching generic `DataAccessException`. Prevent masking unrelated database failures (connection pool timeouts, schema errors, deadlocks) during Just-In-Time (JIT) user provisioning.
* **Issue #53: Audit Integrity: Remove Mutable REST Endpoints from Audit Controllers**: Enforce strict audit log immutability by stripping mutable endpoints (`@Put`, `@Delete`, and public `@Post`) from `OrganizationAuditLogController` and `ProjectAuditLogController`. Restrict audit log generation entirely to internal append-only domain service events dispatched within transactional boundaries.
* **Issue #30: Transaction Context Detachment & Mandatory Test Cleanup in Security Integration Tests**: Resolve connection pool and transaction isolation detachment in Spock integration tests where `@MicronautTest(transactional = true)` hides test fixture inserts from HTTP `@Client` requests executing on separate Netty/Virtual Thread connections. Standardize test fixture seeding with `@MicronautTest(transactional = false)` and enforce mandatory database cleanup (`cleanup:` blocks and table truncation fixtures) across every non-transactional specification to prevent cross-test contamination.

### Architectural Discipline and Standards

In alignment with core architectural discipline and project rules:
1. **Reflection-Free AOT Execution**: All data transfer objects, domain records, and event payloads leverage `@Serdeable` for compile-time Jackson serialization. No runtime reflection or dynamic proxies are used.
2. **Zero Unnecessary Allocations**: High-frequency path operations use unboxed primitives, compact Java records, and direct repository operations to minimize heap churn and garbage collection pressure.
3. **Strict Compile-Time Safety & Explicit Imports**: Fully qualified class names (FQCNs) in source code bodies are strictly forbidden; all imports are explicit. Fail-fast validation annotations (`@NotNull`, `@NotBlank`, `@Valid`) guard all controller and service boundaries.
4. **Declarative Transaction Boundaries**: Database mutations span clear aggregate boundaries managed by `io.micronaut.transaction.annotation.Transactional`, ensuring ACID guarantees.
5. **Virtual Threads Native Execution**: All I/O-bound operations execute on lightweight Java 25 Virtual Threads, maximizing system throughput without reactive complexity.
6. **Deterministic Integration Test Hygiene**: Integration tests running without rollback transactions must explicitly truncate all mutated tables between test executions.

---

## 2. Architectural Design and Component Specifications

### 2.1 Package Organization

The application structure organizes domain business logic, data access, and transport controllers into distinct layers under `lol.pbu.kaiju`:

```
lol.pbu.kaiju
├── controller
│   ├── AdminUserController.java
│   ├── AdministrativeRegionController.java
│   ├── BoundaryController.java
│   ├── LocationController.java
│   ├── OrganizationAuditLogController.java (Read-only)
│   ├── OrganizationController.java
│   ├── OrganizationUserController.java
│   ├── ProjectAuditLogController.java      (Read-only)
│   ├── ProjectController.java
│   ├── RegionUserController.java
│   ├── ShiftController.java
│   ├── TagController.java
│   └── UserController.java
├── domain
│   ├── AdministrativeRegion.java
│   ├── Boundary.java
│   ├── Location.java
│   ├── Organization.java
│   ├── OrganizationAuditLog.java
│   ├── OrganizationUser.java
│   ├── OrganizationUserId.java
│   ├── Project.java
│   ├── ProjectAuditLog.java
│   ├── RegionUser.java
│   ├── RegionUserId.java
│   ├── Shift.java
│   ├── Tag.java
│   └── User.java
├── model
│   ├── AuditAction.java
│   ├── ProjectSearchCard.java
│   ├── ProjectStatus.java
│   ├── ProjectType.java
│   ├── RoleUpdateRequest.java
│   ├── UserRole.java
│   └── VerificationStatus.java
├── repository
│   ├── AdministrativeRegionRepository.java
│   ├── BoundaryRepository.java
│   ├── LocationRepository.java
│   ├── OrganizationAuditLogRepository.java
│   ├── OrganizationRepository.java
│   ├── OrganizationUserRepository.java
│   ├── ProjectAuditLogRepository.java
│   ├── ProjectRepository.java
│   ├── RegionUserRepository.java
│   ├── SecurityQueryRepository.java
│   ├── ShiftRepository.java
│   ├── TagRepository.java
│   └── UserRepository.java
├── security
│   ├── AuthentikAuthenticationMapper.java
│   ├── Permission.java
│   ├── ProjectSecurityService.java
│   └── SecurityRoles.java
└── service
    ├── AuditService.java
    ├── OrganizationService.java
    ├── ProjectService.java
    ├── ShiftService.java
    └── UserService.java
```

### 2.2 Application Service Layer Specifications

Application services encapsulate business validations, cross-entity orchestration, transactional integrity, spatial validation, and internal audit record generation.

```
+-------------------------------------------------------------------------+
|                        REST Controller Layer                            |
|             (@ExecuteOn(TaskExecutors.VIRTUAL), @Secured)               |
+------------------------------------+------------------------------------+
                                     |
                                     | Invokes Service Methods
                                     v
+-------------------------------------------------------------------------+
|                      Application Service Layer                          |
|             (lol.pbu.kaiju.service, @Transactional)                     |
|                                                                         |
|  +-------------------+  +-------------------+  +---------------------+  |
|  |  ProjectService   |  |   ShiftService    |  | OrganizationService |  |
|  +-------------------+  +-------------------+  +---------------------+  |
|  |    UserService    |  |   AuditService    |  |                     |  |
|  +-------------------+  +-------------------+  +---------------------+  |
+-------------------+---------------------------------+-------------------+
                    |                                 |
                    | Direct Data Access              | Append-Only Logging
                    v                                 v
+------------------------------------+ +----------------------------------+
|      Repository Layer (JDBC)       | |      Audit Repositories          |
|    (Atomic delete/remove counts)   | |  (ProjectAuditLog / OrgAuditLog) |
+------------------------------------+ +----------------------------------+
```

#### Service Contract `lol.pbu.kaiju.service.ProjectService`

```java
package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.Project;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface ProjectService {

    @NonNull
    CursoredPage<Project> getProjects(@NonNull CursoredPageable pageable);

    @NonNull
    Optional<Project> getProjectById(@NonNull UUID id);

    @NonNull
    Project createProject(@NonNull Project project, @NonNull UUID actorUserId);

    @NonNull
    Project updateProject(@NonNull UUID id, @NonNull Project project, @NonNull UUID actorUserId);

    void deleteProject(@NonNull UUID id, @NonNull UUID actorUserId);

    @NonNull
    Project approveProject(@NonNull UUID id, @NonNull UUID actorUserId);

    @NonNull
    Project rejectProject(@NonNull UUID id, @NonNull UUID actorUserId);
}
```

#### Implementation Specification `lol.pbu.kaiju.service.ProjectServiceImpl`

Key responsibilities:
- `@Transactional`: All mutating operations (`createProject`, `updateProject`, `deleteProject`, `approveProject`, `rejectProject`) run within a single database transaction.
- Read operations are annotated with `@Transactional(readOnly = true)`.
- Replaces TOCTOU existence checks: On delete, calls `projectRepository.removeById(id)`. If affected rows equal 0, throws `HttpStatusException(HttpStatus.NOT_FOUND, "Project not found")`.
- **Managing Region & Spatial Validation (Issue #27 Migration)**:
  - On `createProject`: Verifies organization presence. If `project.managingRegion()` is non-null, validates that the region exists via `administrativeRegionRepository`, verifies that all project physical locations intersect the region polygon via `securityService.areAllLocationsInRegion(project, regionId)`, and verifies caller authority via `securityService.canAssignManagingRegion(actorUserId, orgId, regionId)`.
  - On `updateProject`: Validates project modification permissions via `securityService.canModifyProject`. Validates organization reassignment authority if changed via `securityService.canReassignProject`. When `managingRegion` is modified, re-evaluates region existence, location spatial intersection, and caller regional assignment authority.
- On creation, evaluates geographic containment using `ProjectSecurityService.evaluateProjectCreationByUser`, sets initial status (`PENDING` or `ACTIVE`), links locations, saves the project, and invokes `AuditService.recordProjectAudit(savedProject, actorUser, AuditAction.CREATED)`.
- On approval, updates project status to `ACTIVE` and records `AuditAction.APPROVED`.
- On rejection, updates status to `REJECTED` and records `AuditAction.REJECTED`.

#### Service Contract `lol.pbu.kaiju.service.OrganizationService`

```java
package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.model.VerificationStatus;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationService {

    @NonNull
    CursoredPage<Organization> getOrganizations(@NonNull CursoredPageable pageable);

    @NonNull
    Optional<Organization> getOrganizationById(@NonNull UUID id);

    @NonNull
    Organization createOrganization(@NonNull Organization organization, @NonNull UUID creatorUserId);

    @NonNull
    Organization updateOrganization(@NonNull UUID id, @NonNull Organization organization, @NonNull UUID actorUserId);

    void deleteOrganization(@NonNull UUID id);

    @NonNull
    Organization updateVerificationStatus(
            @NonNull UUID id,
            @NonNull VerificationStatus newStatus,
            String reason,
            @NonNull UUID actorUserId
    );
}
```

#### Implementation Specification `lol.pbu.kaiju.service.OrganizationServiceImpl`

Key responsibilities:
- `@Transactional`: Mutating operations run within a transaction boundary.
- On deletion: Invokes `organizationRepository.removeById(id)`. If 0 rows affected, throws `HttpStatusException(HttpStatus.NOT_FOUND, "Organization not found")`.
- On verification status update: Fetches existing organization, captures `previousStatus`, applies `newStatus`, saves changes, and records an `OrganizationAuditLog` entry via `AuditService`.
- If an organization update does not modify verification status, performs safe field updates without redundant audit logs.

#### Service Contract `lol.pbu.kaiju.service.ShiftService`

```java
package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.Shift;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface ShiftService {

    @NonNull
    CursoredPage<Shift> getShifts(@NonNull CursoredPageable pageable);

    @NonNull
    Optional<Shift> getShiftById(@NonNull UUID id);

    @NonNull
    Shift createShift(@NonNull Shift shift, @NonNull UUID actorUserId);

    @NonNull
    Shift updateShift(@NonNull UUID id, @NonNull Shift shift, @NonNull UUID actorUserId);

    void deleteShift(@NonNull UUID id, @NonNull UUID actorUserId);
}
```

#### Implementation Specification `lol.pbu.kaiju.service.ShiftServiceImpl`

Key responsibilities:
- Validates shift invariants: For virtual shifts (`isVirtual == true`), `locationId` must be null; for in-person shifts (`isVirtual == false`), `locationId` must be present.
- Validates that `startTime` is before `endTime`.
- Verifies project existence and validates actor permissions via `ProjectSecurityService`.
- Deletion executes `shiftRepository.removeById(id)` atomically; if return count is 0, throws `HttpStatusException(HttpStatus.NOT_FOUND, "Shift not found")`.

#### Service Contract `lol.pbu.kaiju.service.UserService`

```java
package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.UserRole;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface UserService {

    @NonNull
    CursoredPage<User> getUsers(@NonNull CursoredPageable pageable);

    @NonNull
    Optional<User> getUserById(@NonNull UUID id);

    @NonNull
    Optional<User> getUserByEmail(@NonNull String email);

    @NonNull
    User createUser(@NonNull User user);

    @NonNull
    User updateUser(@NonNull UUID id, @NonNull User user);

    void updateRole(@NonNull UUID id, @NonNull UserRole role);

    void deleteUser(@NonNull UUID id);

    @NonNull
    User provisionOrGetUser(@NonNull String email);
}
```

#### Implementation Specification `lol.pbu.kaiju.service.UserServiceImpl`

Key responsibilities:
- Encapsulates JIT provisioning in `provisionOrGetUser`: Attempts user lookup; if absent, attempts to insert `STANDARD_USER`.
- Catches database exceptions, inspects PostgreSQL `SQLState` for code `23505` (`unique_violation`), and fetches the concurrently inserted user without masking unrelated errors.
- Atomic deletion calls `userRepository.removeById(id)`. Throws 404 NOT_FOUND if 0 rows deleted.

#### Service Contract `lol.pbu.kaiju.service.AuditService`

```java
package lol.pbu.kaiju.service;

import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.OrganizationAuditLog;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.ProjectAuditLog;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.AuditAction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface AuditService {

    @NonNull
    ProjectAuditLog recordProjectAudit(
            @NonNull Project project,
            @NonNull User actor,
            @NonNull AuditAction action
    );

    @NonNull
    OrganizationAuditLog recordOrganizationAudit(
            @NonNull Organization organization,
            @NonNull User actor,
            @NonNull String previousStatus,
            @NonNull String newStatus,
            @Nullable String reason
    );
}
```

#### Implementation Specification `lol.pbu.kaiju.service.AuditServiceImpl`

Key responsibilities:
- Direct, append-only persistence of audit log entities within caller transactional boundaries.
- No update or delete operations are exposed on this service.
- Sets `createdAt = OffsetDateTime.now(ZoneOffset.UTC)` deterministically.

---

## 3. Detailed Technical Implementations by Issue

### 3.1 Issue #40: TOCTOU Elimination & Atomic Deletions

#### The Problem

Controllers previously implemented a two-step check-then-act pattern:

```java
// Anti-pattern in existing controllers:
checkExists(repository, id); // Query 1: SELECT count(*) FROM table WHERE id = ?
repository.deleteById(id);   // Query 2: DELETE FROM table WHERE id = ?
```

This pattern introduces two flaws:
1. **TOCTOU Race Condition**: A concurrent transaction could delete the row between Query 1 and Query 2, causing redundant operations and inconsistent audit metrics.
2. **Latency Double-Penalty**: Every delete requires two network round-trips to PostgreSQL instead of one.

#### Atomic Deletion Mechanics in Micronaut Data JDBC

In Micronaut Data JDBC, `CrudRepository` declares `void deleteById(ID id)`. Because Java does not permit changing a super-interface method's return type from `void` to `long`, defining `long deleteById(UUID id)` directly in an interface extending `CrudRepository` produces a compilation error:
`The return expression (Primitive[clazz=long]) doesn't return VOID but method: deleteById returns VOID!`.

Micronaut Data's query generation pattern natively supports the `remove` prefix for delete operations that return affected row counts:

```java
// In repository interfaces:
long removeById(@NonNull UUID id);
```

During compilation, Micronaut Data's annotation processor generates the following query for `removeById`:

```sql
DELETE FROM "tags" WHERE ("id" = ?)
```

The intercepted repository method executes `PreparedStatement.executeUpdate()` and returns the exact number of rows modified by the query (`1` if the record existed, `0` if not).

For composite keys (e.g. `OrganizationUserId`, `RegionUserId`):

```java
long removeById(@NonNull OrganizationUserId id);
// OR derived multi-property queries:
long removeByOrganizationIdAndUserId(@NonNull UUID organizationId, @NonNull UUID userId);
```

#### Atomic Pattern Across Controllers

Controllers and services eliminate `ControllerUtils.checkExists` and execute:

```java
long deletedCount = repository.removeById(id);
if (deletedCount == 0) {
    throw new HttpStatusException(HttpStatus.NOT_FOUND, "Resource not found");
}
```

#### Complete Deletion Plan for `ControllerUtils.java`

1. Remove file: `src/main/java/lol/pbu/kaiju/util/ControllerUtils.java`.
2. Remove `implements ControllerUtils` from all 12 controller classes:
   - `AdminUserController`
   - `AdministrativeRegionController`
   - `BoundaryController`
   - `LocationController`
   - `OrganizationAuditLogController`
   - `OrganizationController`
   - `OrganizationUserController`
   - `ProjectAuditLogController`
   - `RegionUserController`
   - `ShiftController`
   - `TagController`
   - `UserController`
3. Remove import `lol.pbu.kaiju.util.ControllerUtils;` across all controllers.
4. Replace all occurrences of `checkExists(...)` with direct atomic operations or service delegation.

---

### 3.2 Issue #54 & Issue #27: Declarative `@Transactional` Service Layer & Managing Region Validation

#### Design Principles

- Mutating business methods are declared in `lol.pbu.kaiju.service`.
- Classes are annotated with `@Singleton` and methods with `io.micronaut.transaction.annotation.Transactional`.
- Cross-aggregate orchestration (e.g., project status transitions coupled with audit log creation, or managing region PostGIS spatial containment) executes within a single database transaction. If any step fails, the entire transaction rolls back.
- Controllers focus solely on HTTP protocol concerns: request validation, authentication extraction, parameter mapping, and returning HTTP responses.

#### Project Service Implementation (`ProjectServiceImpl`)

```java
package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.AuditAction;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.repository.AdministrativeRegionRepository;
import lol.pbu.kaiju.repository.OrganizationRepository;
import lol.pbu.kaiju.repository.ProjectRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.ProjectSecurityService;
import org.jspecify.annotations.NonNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AdministrativeRegionRepository administrativeRegionRepository;
    private final OrganizationRepository organizationRepository;
    private final ProjectSecurityService securityService;
    private final AuditService auditService;

    public ProjectServiceImpl(
            ProjectRepository projectRepository,
            UserRepository userRepository,
            AdministrativeRegionRepository administrativeRegionRepository,
            OrganizationRepository organizationRepository,
            ProjectSecurityService securityService,
            AuditService auditService) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.administrativeRegionRepository = administrativeRegionRepository;
        this.organizationRepository = organizationRepository;
        this.securityService = securityService;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public CursoredPage<Project> getProjects(@NonNull CursoredPageable pageable) {
        return projectRepository.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public Optional<Project> getProjectById(@NonNull UUID id) {
        return projectRepository.findById(id);
    }

    @Override
    @Transactional
    @NonNull
    public Project createProject(@NonNull Project project, @NonNull UUID actorUserId) {
        if (project.organization() == null || project.organization().id() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Organization is required");
        }

        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        // Spatial & Authority Validation for Managing Region (Issue #27 Migration)
        if (project.managingRegion() != null) {
            UUID regionId = project.managingRegion().id();
            if (regionId == null || !administrativeRegionRepository.existsById(regionId)) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Managing region does not exist");
            }
            if (project.locations() != null && !project.locations().isEmpty()) {
                if (!securityService.areAllLocationsInRegion(project, regionId)) {
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Project locations do not fall within the specified managing region");
                }
            }
            if (!securityService.canAssignManagingRegion(actorUserId, project.organization().id(), regionId)) {
                throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have authority to assign this managing region");
            }
        }

        ProjectStatus initialStatus = securityService.evaluateProjectCreationByUser(actorUserId, project);

        Project toSave = new Project(
                null,
                project.organization(),
                project.managingRegion(),
                project.title(),
                project.description(),
                project.projectType(),
                initialStatus,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null,
                project.locations(),
                project.boundaries()
        );

        Project saved = projectRepository.save(toSave);
        auditService.recordProjectAudit(saved, actor, AuditAction.CREATED);
        return saved;
    }

    @Override
    @Transactional
    @NonNull
    public Project updateProject(@NonNull UUID id, @NonNull Project project, @NonNull UUID actorUserId) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!securityService.canModifyProject(actorUserId, existing)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Not authorized to modify this project");
        }

        Organization targetOrg = existing.organization();
        UUID existingOrgId = existing.organization() != null ? existing.organization().id() : null;

        // Organization Reassignment Validation
        if (project.organization() != null) {
            UUID requestedOrgId = project.organization().id();
            if (!Objects.equals(requestedOrgId, existingOrgId)) {
                if (!securityService.canReassignProject(actorUserId, existing)) {
                    throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have permission to reassign this project to another organization");
                }
                if (requestedOrgId == null) {
                    throw new HttpStatusException(HttpStatus.NOT_FOUND, "Target organization not found");
                }
                targetOrg = organizationRepository.findById(requestedOrgId)
                        .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Target organization not found"));
            }
        }

        // Spatial & Authority Validation for Managing Region on Update (Issue #27 Migration)
        if (project.managingRegion() != null && !Objects.equals(project.managingRegion(), existing.managingRegion())) {
            UUID regionId = project.managingRegion().id();
            if (regionId == null || !administrativeRegionRepository.existsById(regionId)) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Managing region does not exist");
            }
            if (project.locations() != null && !project.locations().isEmpty()) {
                if (!securityService.areAllLocationsInRegion(project, regionId)) {
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Project locations do not fall within the specified managing region");
                }
            }
            UUID effectiveOrgId = targetOrg != null ? targetOrg.id() : (existing.organization() != null ? existing.organization().id() : null);
            if (effectiveOrgId == null || !securityService.canAssignManagingRegion(actorUserId, effectiveOrgId, regionId)) {
                throw new HttpStatusException(HttpStatus.FORBIDDEN, "You do not have authority to assign this managing region");
            }
        }

        Project updated = projectRepository.update(project.withId(id));
        auditService.recordProjectAudit(updated, actor, AuditAction.EDITED);
        return updated;
    }

    @Override
    @Transactional
    public void deleteProject(@NonNull UUID id, @NonNull UUID actorUserId) {
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!securityService.canModifyProject(actorUserId, existing)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Not authorized to delete this project");
        }

        long count = projectRepository.removeById(id);
        if (count == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
    }

    @Override
    @Transactional
    @NonNull
    public Project approveProject(@NonNull UUID id, @NonNull UUID actorUserId) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!securityService.canApproveProject(actorUserId, existing)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Not authorized to approve this project");
        }

        Project approved = projectRepository.update(new Project(
                existing.id(),
                existing.organization(),
                existing.managingRegion(),
                existing.title(),
                existing.description(),
                existing.projectType(),
                ProjectStatus.ACTIVE,
                existing.createdAt(),
                existing.deletedAt(),
                existing.deletedBy(),
                existing.locations(),
                existing.boundaries()
        ));

        auditService.recordProjectAudit(approved, actor, AuditAction.APPROVED);
        return approved;
    }

    @Override
    @Transactional
    @NonNull
    public Project rejectProject(@NonNull UUID id, @NonNull UUID actorUserId) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!securityService.canApproveProject(actorUserId, existing)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Not authorized to reject this project");
        }

        Project rejected = projectRepository.update(new Project(
                existing.id(),
                existing.organization(),
                existing.managingRegion(),
                existing.title(),
                existing.description(),
                existing.projectType(),
                ProjectStatus.REJECTED,
                existing.createdAt(),
                existing.deletedAt(),
                existing.deletedBy(),
                existing.locations(),
                existing.boundaries()
        ));

        auditService.recordProjectAudit(rejected, actor, AuditAction.REJECTED);
        return rejected;
    }
}
```

---

### 3.3 Issue #56: Concurrency: Migrate to Virtual Threads and Eliminate Reactor

#### Background & Motivation

Micronaut applications historically scheduled blocking JDBC I/O on dedicated cached thread pools via `@ExecuteOn(TaskExecutors.BLOCKING)` or `@ExecuteOn(TaskExecutors.IO)`. Under Java 25, the JVM supports native Virtual Threads (Project Loom). Virtual threads provide low-overhead concurrent task execution where blocking on socket or JDBC operations unmounts the carrier thread, allowing massive concurrency without pool exhaustion.

#### 1. Transition `@ExecuteOn` Across All Controllers

Update all controller classes from `@ExecuteOn(TaskExecutors.BLOCKING)` (or `@ExecuteOn(BLOCKING)`) to `@ExecuteOn(TaskExecutors.VIRTUAL)`:

```java
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Controller("/projects")
public class ProjectController { ... }
```

Controllers affected (13 total):
- `AdminUserController`
- `AdministrativeRegionController`
- `BoundaryController`
- `LocationController`
- `OrganizationAuditLogController`
- `OrganizationController`
- `OrganizationUserController`
- `ProjectAuditLogController`
- `ProjectController`
- `RegionUserController`
- `ShiftController`
- `TagController`
- `UserController`

#### 2. Configuration in `application.yml`

Configure virtual thread execution across default Micronaut executors:

```yaml
micronaut:
  server:
    thread-selection: AUTO
  executors:
    io:
      type: VIRTUAL
    scheduled:
      type: VIRTUAL
```

#### 3. Reactor Elimination in `AuthentikAuthenticationMapper`

Currently, `AuthentikAuthenticationMapper` imports `reactor.core.publisher.Mono` and wraps execution in `Mono.fromCallable(...)`.
Under `@ExecuteOn(TaskExecutors.VIRTUAL)`, the mapper method executes on a virtual thread. We replace `Mono.fromCallable` with direct virtual-thread execution returning `Publishers.just(AuthenticationResponse.success(...))`:

```java
package lol.pbu.kaiju.security;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.oauth2.endpoint.authorization.state.State;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdAuthenticationMapper;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.service.UserService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import java.util.Map;

@Named("authentik")
@Singleton
@ExecuteOn(TaskExecutors.VIRTUAL)
public class AuthentikAuthenticationMapper implements OpenIdAuthenticationMapper {

    private final UserService userService;

    public AuthentikAuthenticationMapper(UserService userService) {
        this.userService = userService;
    }

    @Override
    @NonNull
    public Publisher<AuthenticationResponse> createAuthenticationResponse(
            @NonNull String providerName,
            @NonNull OpenIdTokenResponse tokenResponse,
            @NonNull OpenIdClaims openIdClaims,
            @Nullable State state) {

        String email = openIdClaims.getEmail();
        if (email == null || email.isBlank()) {
            return Publishers.just(AuthenticationResponse.failure("No email present in OpenID claims"));
        }

        User user = userService.provisionOrGetUser(email);

        AuthenticationResponse response = AuthenticationResponse.success(
                user.id().toString(),
                user.role().getPermissions().stream().map(Permission::getClaim).toList(),
                Map.of("email", user.email())
        );

        return Publishers.just(response);
    }
}
```

#### 4. Dependency Removal in `build.gradle.kts`

Remove the Reactor HTTP client dependency:

```kotlin
// REMOVE THIS LINE:
// implementation("io.micronaut.reactor:micronaut-reactor-http-client")
```

Verify that no other Reactor classes remain in `src/main` or `src/test`.

---

### 3.4 Issue #23: Robust Postgres SQLState Error Handling in `AuthentikAuthenticationMapper`

#### The Problem

In the existing codebase, JIT user provisioning caught generic `DataAccessException`:

```java
try {
    User newUser = new User(null, email, STANDARD_USER, OffsetDateTime.now(systemDefault()));
    user = userRepository.save(newUser);
} catch (io.micronaut.data.exceptions.DataAccessException e) {
    // Naively assumes unique constraint violation on email:
    user = userRepository.findByEmail(email)
        .orElseThrow(() -> new IllegalStateException("Failed to fetch user after constraint violation", e));
}
```

If the database threw an exception due to connection exhaustion, network disruption, syntax error, foreign key failure, or deadlocks, the exception was caught, assumed to be a unique violation, and immediately triggered `findByEmail()`, producing misleading error messages and hiding the root cause.

#### PostgreSQL Error Code Inspection

The PostgreSQL standard error code for unique key violation is `23505` (`unique_violation`), declared in `org.postgresql.util.PSQLState.UNIQUE_VIOLATION.getState()`.

We implement a causal-chain inspection utility:

```java
package lol.pbu.kaiju.service;

import io.micronaut.data.exceptions.DataAccessException;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.UserRole;
import lol.pbu.kaiju.repository.UserRepository;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Singleton
public class UserServiceImpl implements UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserServiceImpl.class);
    private static final String SQL_STATE_UNIQUE_VIOLATION = PSQLState.UNIQUE_VIOLATION.getState(); // "23505"

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public User provisionOrGetUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            try {
                User newUser = new User(null, email, UserRole.STANDARD_USER, OffsetDateTime.now(ZoneOffset.UTC));
                return userRepository.save(newUser);
            } catch (DataAccessException dae) {
                if (isUniqueConstraintViolation(dae)) {
                    LOG.info("Concurrent user insertion detected for email: {}. Retrieving existing record.", email);
                    return userRepository.findByEmail(email)
                            .orElseThrow(() -> new IllegalStateException("User record could not be found after unique violation on email: " + email, dae));
                }
                LOG.error("Database error occurred during user provisioning for email: {}", email, dae);
                throw dae;
            }
        });
    }

    private boolean isUniqueConstraintViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PSQLException psqlException) {
                if (SQL_STATE_UNIQUE_VIOLATION.equals(psqlException.getSQLState())) {
                    return true;
                }
            } else if (current instanceof SQLException sqlException) {
                if (SQL_STATE_UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
```

#### Fail-Fast Guarantees

- If SQLState is `23505`, handle the race condition cleanly.
- If SQLState is anything else (`08006` connection failure, `57P01` admin shutdown, `40P01` deadlock), fail fast by re-throwing `dae`.

---

### 3.5 Issue #53: Audit Integrity: Remove Mutable REST Endpoints and Enforce Append-Only Events

#### The Threat Vector

Previously, `OrganizationAuditLogController` and `ProjectAuditLogController` exposed `@Put("/{id}")` and `@Delete("/{id}")` endpoints, allowing authenticated callers to alter or delete historical audit entries. Furthermore, exposing a public `@Post` endpoint allowed callers to fabricate false audit entries.

#### Hardened Read-Only Controllers

1. **`OrganizationAuditLogController`**:
   - Retain:
     - `@Get`: Lists paginated organization audit logs.
     - `@Get("/{id}")`: Retrieves a specific audit log by ID.
   - Delete:
     - `@Put("/{id}")`
     - `@Delete("/{id}")`
     - `@Post`
   - Secure with discrete permission claims (e.g., `@Secured({"system:admin", "org:audit:read"})`).

2. **`ProjectAuditLogController`**:
   - Retain:
     - `@Get`: Lists paginated project audit logs.
     - `@Get("/{id}")`: Retrieves a specific audit log by ID.
   - Delete:
     - `@Put("/{id}")`
     - `@Delete("/{id}")`
     - `@Post`
   - Secure with discrete permission claims (e.g., `@Secured({"system:admin", "project:audit:read"})`).

#### Append-Only Audit Service Implementation

Audit log entries are generated strictly within `AuditServiceImpl` during transactional service mutations:

```java
package lol.pbu.kaiju.service;

import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.OrganizationAuditLog;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.ProjectAuditLog;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.AuditAction;
import lol.pbu.kaiju.repository.OrganizationAuditLogRepository;
import lol.pbu.kaiju.repository.ProjectAuditLogRepository;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Singleton
public class AuditServiceImpl implements AuditService {

    private final ProjectAuditLogRepository projectAuditLogRepository;
    private final OrganizationAuditLogRepository organizationAuditLogRepository;

    public AuditServiceImpl(
            ProjectAuditLogRepository projectAuditLogRepository,
            OrganizationAuditLogRepository organizationAuditLogRepository) {
        this.projectAuditLogRepository = projectAuditLogRepository;
        this.organizationAuditLogRepository = organizationAuditLogRepository;
    }

    @Override
    @Transactional
    @NonNull
    public ProjectAuditLog recordProjectAudit(
            @NonNull Project project,
            @NonNull User actor,
            @NonNull AuditAction action) {
        ProjectAuditLog log = new ProjectAuditLog(
                null,
                project,
                actor,
                action,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return projectAuditLogRepository.save(log);
    }

    @Override
    @Transactional
    @NonNull
    public OrganizationAuditLog recordOrganizationAudit(
            @NonNull Organization organization,
            @NonNull User actor,
            @NonNull String previousStatus,
            @NonNull String newStatus,
            @Nullable String reason) {
        OrganizationAuditLog log = new OrganizationAuditLog(
                null,
                organization,
                actor,
                previousStatus,
                newStatus,
                reason,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return organizationAuditLogRepository.save(log);
    }
}
```

---

### 3.6 Issue #30: Transaction Context Detachment & Mandatory Test Cleanup

#### The Problem

In Spock integration tests:
1. `BaseControllerSpec` was annotated with `@MicronautTest(transactional = true)`.
2. When `@MicronautTest(transactional = true)` is active, Micronaut wraps each Spock feature in a thread-local transaction that rolls back at the end of the test.
3. When test methods invoke the application via `@Client("/") HttpClient`, the HTTP request runs on a distinct thread pool / virtual thread connection.
4. Under PostgreSQL default `READ COMMITTED` isolation, uncommitted inserts from the test method's thread-local transaction are completely invisible to the server's connection!
5. This forced test authors to bypass Micronaut transaction management by hacking `rawDataSource` to run auto-commit queries, leading to flaky tests, dangling state, and connection leaks.

#### The Architectural Solution

1. **Differentiate Test Scenarios**:
   - **Integration Tests using `@Client`**: Must specify `@MicronautTest(transactional = false)`. Because HTTP requests execute across thread boundaries, transactions cannot be shared between client and server.
   - **Direct Service Tests**: Test `@Transactional` application services directly without HTTP overhead. These can use `@MicronautTest(transactional = true)` for automatic rollback isolation.
2. **Synchronize Fixture Seeding via `BaseControllerSpec`**:
   Refactor `BaseControllerSpec` to inject `DataSource` and `SynchronousTransactionManager<Connection>`.
3. **Mandatory Database Cleanup Protocol**:
   To prevent cross-test contamination in non-transactional specs (`transactional = false`), every non-transactional test class must execute an explicit database cleanup routine. `BaseControllerSpec` provides a centralized `cleanupDatabase()` method executing a fast `TRUNCATE TABLE ... CASCADE` across all domain tables.

```groovy
package lol.pbu.kaiju.controller

import groovy.sql.Sql
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.transaction.SynchronousTransactionManager
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.sql.Connection

@MicronautTest(transactional = false)
class BaseControllerSpec extends Specification {

    @Inject
    @Shared
    DataSource dataSource

    @Inject
    @Shared
    SynchronousTransactionManager<Connection> transactionManager

    Sql getSql() {
        return new Sql(dataSource)
    }

    protected void executeUpdate(String sqlString, Object... parameters) {
        sql.execute(sqlString, parameters as List)
    }

    protected <T> T inTransaction(Closure<T> closure) {
        return transactionManager.executeWrite { status ->
            closure.call(status)
        }
    }

    /**
     * Centralized database truncation fixture for non-transactional test specifications.
     * Wipes all transient test data across domain tables using CASCADE.
     */
    protected void cleanupDatabase() {
        executeUpdate("""
            TRUNCATE TABLE 
                project_audit_logs,
                organization_audit_logs,
                shift_signups,
                shift_tags,
                shifts,
                project_locations,
                project_boundaries,
                project_users,
                projects,
                organization_regions,
                organization_locations,
                organization_users,
                region_users,
                organizations,
                locations,
                boundaries,
                administrative_regions,
                tags,
                users
            CASCADE
        """)
    }

    def cleanup() {
        cleanupDatabase()
    }
}
```

---

## 4. Step-by-Step Implementation Strategy

```
+-------------------------------------------------------------------------+
| Phase 1: Repository Enhancements & ControllerUtils Elimination (#40)    |
| - Add removeById() returning long across all repositories                |
| - Delete ControllerUtils.java and remove implements ControllerUtils     |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| Phase 2: Application Service Layer & Declarative Transactions (#54)     |
| - Create lol.pbu.kaiju.service package                                  |
| - Implement ProjectService, ShiftService, OrganizationService, etc.     |
| - Migrate managingRegion spatial validation from controller (#27)       |
| - Annotate methods with @Transactional boundaries                       |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| Phase 3: Audit Log Hardening & Immutability (#53)                       |
| - Strip @Put, @Delete, @Post from AuditLogControllers                   |
| - Implement AuditService for internal append-only event logging         |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| Phase 4: Virtual Threads & Reactor Elimination (#56)                    |
| - Replace @ExecuteOn(BLOCKING) with @ExecuteOn(TaskExecutors.VIRTUAL)   |
| - Refactor AuthentikAuthenticationMapper to use virtual threads         |
| - Remove micronaut-reactor dependency from build.gradle.kts             |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| Phase 5: PostgreSQL SQLState Error Handling in User Provisioning (#23)   |
| - Inspect SQLState 23505 in UserServiceImpl                             |
| - Add unit and integration tests for concurrent unique violation        |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| Phase 6: Spock Test Synchronization & Mandatory Cleanup (#30)           |
| - Configure @MicronautTest(transactional = false) for @Client specs     |
| - Implement cleanupDatabase() truncation in BaseControllerSpec          |
| - Mandate explicit cleanup blocks in non-transactional specs            |
| - Run complete test suite and performance benchmarks                    |
+-------------------------------------------------------------------------+
```

### Phase 1: Repository Enhancements and `ControllerUtils` Elimination

1. Add `long removeById(@NonNull UUID id)` to repositories:
   - `AdministrativeRegionRepository`
   - `BoundaryRepository`
   - `LocationRepository`
   - `OrganizationAuditLogRepository`
   - `OrganizationRepository`
   - `ProjectAuditLogRepository`
   - `ProjectRepository`
   - `ShiftRepository`
   - `TagRepository`
   - `UserRepository`
2. Add composite key remove methods:
   - `OrganizationUserRepository`: `long removeById(@NonNull OrganizationUserId id)`
   - `RegionUserRepository`: `long removeById(@NonNull RegionUserId id)`
3. Delete `src/main/java/lol/pbu/kaiju/util/ControllerUtils.java`.
4. Remove `implements ControllerUtils` from all 12 controller classes.
5. Replace `checkExists` calls with atomic deletion or `findById().orElseThrow()`.

### Phase 2: Application Service Layer, Transactions, and Managing Region Validation

1. Create package `src/main/java/lol/pbu/kaiju/service`.
2. Define service interfaces and implementations:
   - `ProjectService` & `ProjectServiceImpl`: Enforces managing region existence, PostGIS spatial boundary containment via `areAllLocationsInRegion`, and caller authority via `canAssignManagingRegion` (Issue #27 migration).
   - `ShiftService` & `ShiftServiceImpl`
   - `OrganizationService` & `OrganizationServiceImpl`
   - `UserService` & `UserServiceImpl`
   - `AuditService` & `AuditServiceImpl`
3. Refactor `ProjectController`, `ShiftController`, `OrganizationController`, `UserController`, `AdminUserController` to inject and delegate to their respective services.

### Phase 3: Audit Log Hardening and Immutability

1. In `OrganizationAuditLogController`:
   - Delete `updateOrganizationAuditLog` (`@Put("/{id}")`).
   - Delete `deleteOrganizationAuditLog` (`@Delete("/{id}")`).
   - Delete `addOrganizationAuditLog` (`@Post`).
2. In `ProjectAuditLogController`:
   - Delete `updateProjectAuditLog` (`@Put("/{id}")`).
   - Delete `deleteProjectAuditLog` (`@Delete("/{id}")`).
   - Delete `addProjectAuditLog` (`@Post`).
3. Wire domain mutations in `ProjectServiceImpl` and `OrganizationServiceImpl` to call `AuditService` within the same `@Transactional` boundary.

### Phase 4: Virtual Threads and Reactor Elimination

1. Replace all `@ExecuteOn(TaskExecutors.BLOCKING)` and `@ExecuteOn(BLOCKING)` with `@ExecuteOn(TaskExecutors.VIRTUAL)` across all 13 controllers.
2. In `AuthentikAuthenticationMapper`:
   - Change annotation to `@ExecuteOn(TaskExecutors.VIRTUAL)`.
   - Delegate provisioning to `UserService.provisionOrGetUser(email)`.
   - Return `Publishers.just(AuthenticationResponse.success(...))`.
3. In `build.gradle.kts`:
   - Remove `implementation("io.micronaut.reactor:micronaut-reactor-http-client")`.
4. In `AuthentikAuthenticationMapperSpec.groovy`:
   - Refactor tests to consume the `Publisher` using standard `CompletableFuture` or reactive subscriber without Reactor.

### Phase 5: PostgreSQL SQLState Error Handling

1. In `UserServiceImpl.provisionOrGetUser`:
   - Unwrap exceptions down the causal chain.
   - Specifically check for `PSQLException` or `SQLException` with `SQLState` equal to `23505`.
   - If matched, re-query `findByEmail`. If not matched, re-throw immediately.
2. Write unit tests in `UserServiceSpec` and integration tests in `AuthentikAuthenticationMapperSpec` verifying:
   - Success on first try.
   - Successful resolution when `23505` is caught.
   - Fail-fast propagation when non-`23505` error occurs (e.g. connection error).

### Phase 6: Spock Test Synchronization and Mandatory Cleanup

1. Update `BaseControllerSpec.groovy`:
   - Set `@MicronautTest(transactional = false)`.
   - Remove `rawDataSource` reflection workaround.
   - Implement `cleanupDatabase()` with table cascade truncation.
   - Define `cleanup()` hook calling `cleanupDatabase()`.
2. Mandate explicit `cleanup:` or `cleanup()` in every non-transactional specification.
3. Verify all existing Spock tests pass.
4. Validate with `./gradlew check` and `./gradlew lighthouse`.

---

## 5. Comprehensive Testing and Verification Plan

### 5.1 Test Coverage Matrix

| Area | Component | Key Scenarios | Expected Behavior |
| :--- | :--- | :--- | :--- |
| **Atomic Delete** | All CRUD Controllers | Delete non-existent ID | `404 NOT_FOUND` |
| **Atomic Delete** | All CRUD Controllers | Delete existing ID | `200 OK` or `204 NO_CONTENT`, count = 1 |
| **Atomic Delete** | Concurrency Test | Concurrent deletes of same ID | Exactly one returns success, other returns 404 |
| **Managing Region**| `ProjectService` | Create project with non-existent managing region | `400 BAD_REQUEST` ("Managing region does not exist") |
| **Managing Region**| `ProjectService` | Create project with locations outside managing region | `400 BAD_REQUEST` ("Project locations do not fall within...") |
| **Managing Region**| `ProjectService` | Create project without regional assignment authority | `403 FORBIDDEN` ("You do not have authority to assign...") |
| **Transactions** | `ProjectService` | Create project fails during audit logging | Project record is rolled back, not persisted |
| **Transactions** | `OrganizationService`| Verification status update | Status updated AND audit log inserted atomically |
| **Virtual Threads**| All Controllers | High-concurrency load (500+ virtual threads) | Zero thread pool exhaustion, latency flat |
| **Reactor Removal**| `AuthentikMapper` | Token claims mapping | Returns `Publisher` without Reactor classes |
| **SQLState 23505** | `UserService` | Concurrent user insert with same email | Resolves existing user cleanly |
| **SQLState Masking**| `UserService` | Connection timeout / fatal error | Fails fast, does NOT attempt findByEmail |
| **Audit Immutability**| `AuditLogControllers` | HTTP PUT to `/organization-audit-logs/{id}` | `405 METHOD_NOT_ALLOWED` or `404` |
| **Audit Immutability**| `AuditLogControllers` | HTTP DELETE to `/project-audit-logs/{id}` | `405 METHOD_NOT_ALLOWED` or `404` |
| **Audit Immutability**| `AuditLogControllers` | HTTP POST to `/project-audit-logs` | `405 METHOD_NOT_ALLOWED` or `404` |
| **Test Fixtures** | Spock `@Client` Specs | Seed fixture and query via `@Client` | Fixture visible immediately across threads |
| **Test Hygiene** | Non-Transactional Specs | Database cleanup between tests | Tables truncated, zero state contamination |

### 5.2 Atomic Deletion Verification Spec (`AtomicDeletionSpec.groovy`)

```groovy
package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Tag
import lol.pbu.kaiju.repository.TagRepository

@MicronautTest(transactional = false)
class AtomicDeletionSpec extends BaseControllerSpec {

    @Inject
    @Client("/")
    HttpClient httpClient

    @Inject
    TagRepository tagRepository

    def cleanup() {
        cleanupDatabase()
    }

    def "DELETE | should return 404 NOT_FOUND atomically when resource does not exist"() {
        given: "a random UUID that does not exist in the database"
        def nonExistentId = UUID.randomUUID()

        when: "attempting to delete the resource via HTTP DELETE"
        httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/tags/${nonExistentId}")
                        .header("X-Test-User", "admin-user")
                        .header("X-Test-Role", "system:admin")
        )

        then: "a 404 NOT_FOUND exception is returned directly without TOCTOU race"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND

        cleanup:
        cleanupDatabase()
    }

    def "DELETE | should return 200 or 204 and remove record atomically when resource exists"() {
        given: "a persisted tag entity"
        def tag = tagRepository.save(new Tag(null, "atomic-test-${UUID.randomUUID()}"))

        when: "deleting the tag via HTTP DELETE"
        def response = httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/tags/${tag.id()}")
                        .header("X-Test-User", "admin-user")
                        .header("X-Test-Role", "system:admin")
        )

        then: "response indicates success"
        response.status in [HttpStatus.OK, HttpStatus.NO_CONTENT]

        and: "the record is removed from the database"
        !tagRepository.findById(tag.id()).isPresent()

        cleanup:
        cleanupDatabase()
    }
}
```

### 5.3 Managing Region Spatial Validation Spec (`ProjectServiceSpatialSpec.groovy`)

```groovy
package lol.pbu.kaiju.service

import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.UserRepository
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.io.WKTReader

@MicronautTest(transactional = false)
class ProjectServiceSpatialSpec extends BaseControllerSpec {

    @Inject
    ProjectService projectService

    @Inject
    AdministrativeRegionRepository regionRepository

    @Inject
    OrganizationRepository organizationRepository

    @Inject
    UserRepository userRepository

    def cleanup() {
        cleanupDatabase()
    }

    def "ManagingRegion | rejects creation when managing region does not exist"() {
        given: "an organization and creator user"
        def user = saveUser(UserRole.GLOBAL_ADMIN)
        def org = saveOrganization("Test Org")

        and: "a dummy managing region that does not exist in DB"
        def nonExistentRegion = new AdministrativeRegion(UUID.randomUUID(), "Ghost Region", null, null)
        def project = new Project(null, org, nonExistentRegion, "Title", "Desc", ProjectType.STANDARD, null, null, null, null, [], [])

        when: "creating the project"
        projectService.createProject(project, user.id())

        then: "a BAD_REQUEST exception is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.BAD_REQUEST
        e.message == "Managing region does not exist"

        cleanup:
        cleanupDatabase()
    }

    def "ManagingRegion | rejects creation when project locations fall outside managing region polygon"() {
        given: "a region polygon (Denver, CO)"
        def regId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Denver', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        def region = regionRepository.findById(regId).get()

        and: "an organization linked to the region"
        def org = saveOrganization("Denver Org")
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org.id(), regId)

        and: "a user with region agent authority"
        def agent = saveUser(UserRole.REGION_AGENT)
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_AGENT')", agent.id(), regId)

        and: "a location outside the region (Boulder, CO: -105.27, 40.01)"
        def gf = new GeometryFactory(new PrecisionModel(), 4326)
        def outsideLoc = new Location(UUID.randomUUID(), "Boulder Out", "St", "Boulder", null, null, "US", gf.createPoint(new Coordinate(-105.27, 40.01)))
        def project = new Project(null, org, region, "Out Project", "Desc", ProjectType.STANDARD, null, null, null, null, [outsideLoc], [])

        when: "creating the project"
        projectService.createProject(project, agent.id())

        then: "a BAD_REQUEST is thrown for locations not falling in managing region"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.BAD_REQUEST
        e.message.contains("Project locations do not fall within the specified managing region")

        cleanup:
        cleanupDatabase()
    }

    def "ManagingRegion | rejects creation when caller lacks authority to assign managing region"() {
        given: "a region and organization"
        def regId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Authorized Reg', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        def region = regionRepository.findById(regId).get()
        def org = saveOrganization("Auth Org")
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org.id(), regId)

        and: "a standard user without admin/manager roles"
        def standardUser = saveUser(UserRole.STANDARD_USER)
        def project = new Project(null, org, region, "Unauthorized Project", "Desc", ProjectType.STANDARD, null, null, null, null, [], [])

        when: "creating the project"
        projectService.createProject(project, standardUser.id())

        then: "a FORBIDDEN exception is thrown"
        def e = thrown(HttpStatusException)
        e.status == HttpStatus.FORBIDDEN
        e.message == "You do not have authority to assign this managing region"

        cleanup:
        cleanupDatabase()
    }
}
```

### 5.4 Audit Immutability Verification Spec (`AuditImmutabilitySpec.groovy`)

```groovy
package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Unroll

@MicronautTest(transactional = false)
class AuditImmutabilitySpec extends BaseControllerSpec {

    @Inject
    @Client("/")
    HttpClient httpClient

    def cleanup() {
        cleanupDatabase()
    }

    @Unroll
    def "AUDIT IMMUTABILITY | #method #endpoint should be rejected (404 or 405)"() {
        when: "attempting a forbidden mutation on audit log endpoints"
        def request = HttpRequest.create(httpMethod, endpoint)
                .header("X-Test-User", "global-admin")
                .header("X-Test-Role", "system:admin")
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .body("{}")

        httpClient.toBlocking().exchange(request)

        then: "the server rejects the mutation because endpoints are deleted"
        def e = thrown(HttpClientResponseException)
        e.status in [HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND]

        cleanup:
        cleanupDatabase()

        where:
        method   | httpMethod                          | endpoint
        "PUT"    | io.micronaut.http.HttpMethod.PUT    | "/project-audit-logs/${UUID.randomUUID()}"
        "DELETE" | io.micronaut.http.HttpMethod.DELETE | "/project-audit-logs/${UUID.randomUUID()}"
        "POST"   | io.micronaut.http.HttpMethod.POST   | "/project-audit-logs"
        "PUT"    | io.micronaut.http.HttpMethod.PUT    | "/organization-audit-logs/${UUID.randomUUID()}"
        "DELETE" | io.micronaut.http.HttpMethod.DELETE | "/organization-audit-logs/${UUID.randomUUID()}"
        "POST"   | io.micronaut.http.HttpMethod.POST   | "/organization-audit-logs"
    }
}
```

### 5.5 Concurrent JIT User Provisioning Spec (`UserServiceConcurrencySpec.groovy`)

```groovy
package lol.pbu.kaiju.service

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.repository.UserRepository

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

@MicronautTest(transactional = false)
class UserServiceConcurrencySpec extends BaseControllerSpec {

    @Inject
    UserService userService

    @Inject
    UserRepository userRepository

    def cleanup() {
        cleanupDatabase()
    }

    def "JIT Provisioning | handles concurrent insertion for same email gracefully via SQLState 23505"() {
        given: "a shared email address and virtual thread executor"
        def email = "concurrent-${UUID.randomUUID()}@example.com"
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        int concurrency = 16

        when: "16 virtual threads attempt to provision the same user concurrently"
        List<Callable<User>> tasks = (1..concurrency).collect {
            return { -> userService.provisionOrGetUser(email) } as Callable<User>
        }
        List<Future<User>> futures = executor.invokeAll(tasks)
        List<User> results = futures.collect { it.get() }

        then: "all tasks succeed and return the exact same user ID"
        results.size() == concurrency
        def firstUserId = results[0].id()
        results.every { it.id() == firstUserId }
        results.every { it.email() == email }

        and: "only exactly one user row was inserted into the database"
        userRepository.findByEmail(email).isPresent()

        cleanup:
        cleanupDatabase()
    }
}
```

---

## 6. Risk Analysis and Mitigations

| Risk | Impact | Probability | Mitigation Strategy |
| :--- | :--- | :--- | :--- |
| **Micronaut Data Repository Method Name Mismatch** | Medium | Low | Use standard `removeById` which Micronaut Data natively compiles into `DELETE FROM table WHERE id = ?` returning row count `long`. Validate query generation during `./gradlew compileJava`. |
| **Virtual Thread Carrier Pinning (HikariCP / PostgreSQL Driver)** | Medium | Low | PostgreSQL JDBC driver versions 42.6+ avoid carrier thread pinning on standard socket read/write operations. Monitor `-Djdk.tracePinnedThreads=full` during performance load tests. |
| **Transaction Rollback in Service Multi-Entity Mutations** | High | Low | Mark mutating service methods with `@Transactional`. Ensure all entity modifications and audit log creations use the same database connection pool. |
| **Managing Region Validation Bypass** | High | Low | Migrate Issue #27 spatial and authority checks directly into `ProjectServiceImpl.createProject` and `updateProject`, ensuring all callers (HTTP, batch, async) are strictly checked inside the transactional boundary. |
| **Test Fixture Pollution with `transactional = false`** | High | Medium | Mandate explicit `cleanupDatabase()` with table cascade truncation in `cleanup:` blocks of all non-transactional specifications to guarantee clean database states. |
| **Uncaught Exception Types During SQLState Inspection** | High | Low | Recursively unwrap the `Throwable` causal chain down to root causes, checking both `PSQLException` and `SQLException` for code `23505`. Fail fast on any other error. |

---

## 7. Verification Gates and Performance Benchmarking Strategy

### 7.1 Quality Verification Gates

All changes must pass five strict verification gates before Release 0.0.5 is considered complete:

```
+-------------------------------------------------------------------------+
|                  Gate 1: Lighthouse Markdown Audit                      |
|                  ./gradlew lighthouse                                   |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                  Gate 2: Clean Compilation & AOT Check                  |
|                  ./gradlew compileJava compileTestGroovy                |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                  Gate 3: Spock Test Suite Pass Rate                     |
|                  ./gradlew test                                         |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                  Gate 4: JaCoCo Coverage & SonarQube Gate               |
|                  ./gradlew jacocoTestReport sonar                       |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                  Gate 5: Concurrency & Virtual Thread Benchmark         |
|                  500 Concurrent Connections / Load Verification         |
+------------------------------------+------------------------------------+
```

1. **Gate 1: Lighthouse Markdown Audit**:
   - Command: `./gradlew lighthouse`
   - Requirement: 100% pass on syntax, code block balancing, blank line formatting, and link integrity.
2. **Gate 2: Clean Compilation & AOT Check**:
   - Command: `./gradlew compileJava compileTestGroovy`
   - Requirement: Zero warnings, zero inline FQCNs, explicit imports only, zero compile-time errors.
3. **Gate 3: Spock Test Suite Pass Rate**:
   - Command: `./gradlew test`
   - Requirement: 100% test pass rate across all controller specs, security matrix specs, spatial validation specs, and service specs.
4. **Gate 4: JaCoCo Coverage & SonarQube Gate**:
   - Command: `./gradlew jacocoTestReport sonar`
   - Requirement: Zero blocker/critical issues; line coverage maintained or increased across modified packages.
5. **Gate 5: Concurrency & Virtual Thread Benchmark**:
   - Requirement: Load test simulating 500 concurrent virtual threads performing CRUD requests. Verify zero connection pool timeouts, sub-50ms p95 latency, and zero carrier thread pinning.

### 7.2 Performance Benchmarking Strategy

To validate the performance benefits of migrating from `TaskExecutors.BLOCKING` to Java 25 Virtual Threads (`TaskExecutors.VIRTUAL`), execute a comparative load test:

- **Benchmark Tool**: JMH or `k6` / `gatling` script hitting `/projects` and `/shifts`.
- **Concurrency Targets**: 50, 100, 250, and 500 concurrent connections.
- **Metrics Collected**:
  - Request Throughput (Requests Per Second)
  - Latency Distribution (p50, p90, p95, p99)
  - JVM Thread Count and Memory Consumption (heap and non-heap)
  - Carrier thread pinning incidents (via JVM flag `-Djdk.tracePinnedThreads=full`)
- **Success Criteria**:
  - Virtual Threads maintain constant throughput under high concurrency where legacy blocking thread pools experience thread pool starvation.
  - p99 latency under 500 concurrent requests remains within 2x of baseline single-thread latency.
  - Zero deadlocks or TOCTOU race conditions observed under concurrent delete and update operations.
