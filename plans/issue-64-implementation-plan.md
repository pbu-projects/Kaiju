# Technical Implementation Plan: Release 0.1.2 (Issue #64)

**API Boundaries, DTO Records, Compile-Time Serde & Pipeline Test Migration**

---

## 1. Executive Summary & Release Scope

### 1.1 Scope & Mission

Release 0.1.2 establishes strict architectural boundaries across the Kaiju platform by eliminating domain model leakage in the REST API, securing child entity mass-assignment vectors, enforcing bean validation constraints, enabling reflection-free Micronaut AOT/Serde introspection, and migrating all Spock controller specifications to full Netty HTTP pipeline integration tests.

This release incorporates core architectural principles with three specific design enhancements:
- **Enhancement 1 (Coordinate Bounds Validation)**: Mandatory `@Min(-180) @Max(180)` on longitude and `@Min(-90) @Max(90)` on latitude across all spatial DTOs.
- **Enhancement 2 (Role Escalation Guard on CreateUserCommand)**: Enforces that user registration/creation defaults to `STANDARD_USER` unless an authenticated caller possesses `SYSTEM_USER_MANAGE_CLAIM`.
- **Enhancement 3 (CDI Singleton GeometryFactory Lifecycle)**: Eliminates `new GeometryFactory()` instantiations by introducing an injected `@Singleton SpatialMappingService`.

This release consolidates and resolves six core issues:
- **[#51] AOT & Serde Configuration**: Enable compile-time `@Serdeable` introspection across all domain entities, models, and DTOs. Optimize `build.gradle.kts` by removing redundant annotation processors (`ch.qos.logback:logback-classic`), eliminating `snakeyaml` from runtime classpath, enabling `convertYamlToJava = true` and `optimizeServiceLoading = true` in the Micronaut AOT configuration, and pinning `org.sonarqube` plugin.
- **[#55] API Boundaries & DTO Records**: Replace `@MappedEntity` request body bindings across all 13 REST controllers with dedicated Command / DTO records in package `lol.pbu.kaiju.dto`.
- **[#26] Mass Assignment Vulnerability on Child Entities**: Eliminate child entity ID injection in locations and boundaries during project creation and update by omitting identity fields from child DTOs and strictly generating or managing IDs server-side.
- **[#41] Non-Empty Project Boundaries**: Enforce `@NotEmpty` validation on project boundary commands to eliminate boundary-less projects.
- **[#42] Project Description Length Bounds**: Enforce `@Size(min = 20, max = 4000)` on project description across both Command DTOs and domain entities.
- **[#52] Full HTTP Pipeline Test Migration**: Migrate all Spock controller specifications from direct Java controller method invocations to full Netty HTTP client integration tests using `@Client("/")` and `BlockingHttpClient`, validating end-to-end serialization, Bean Validation (400 Bad Request), authentication (401), and authorization intercepts (403).

### 1.2 Architectural Principles

1. **Zero Reflection at Runtime**: Every serialization, deserialization, and bean validation pathway must be pre-compiled via Micronaut AOT, `micronaut-serde-processor`, and `micronaut-validation-processor`.
2. **Zero Unnecessary Allocations**: Canonical constructor mapping and direct factory methods (`toEntity()`) avoid reflection-based object mappers, dynamic proxies, and intermediary wrappers.
3. **Strict Compile-Time Safety & Explicit Imports**: Disallow inline fully-qualified class names (FQCNs) and wildcard imports. All types must be explicitly imported and checked by javac at compile time.
4. **Defensive API Invariants**: Public HTTP contracts never expose or ingest internal database identifiers or unmanaged entity relations. IDs on mutated resources must derive strictly from authenticated security contexts or URL path variables.

```
                      HTTP / Netty Pipeline
                               │
            ┌──────────────────▼──────────────────┐
            │   Micronaut Security & Auth Filter  │  (401 / 403 Intercept)
            └──────────────────┬──────────────────┘
                               │
            ┌──────────────────▼──────────────────┐
            │   Serde Jackson Deserialization     │  (@Serdeable DTOs)
            └──────────────────┬──────────────────┘
                               │
            ┌──────────────────▼──────────────────┐
            │  Bean Validation Interceptor (AOT)  │  (400 Bad Request)
            │  @Valid, @Size, @NotEmpty, @NotNull │
            └──────────────────┬──────────────────┘
                               │
            ┌──────────────────▼──────────────────┐
            │         REST Controllers            │  (lol.pbu.kaiju.controller)
            │      Accept DTO Command Records     │
            └──────────────────┬──────────────────┘
                               │ Compile-time toEntity()
            ┌──────────────────▼──────────────────┐
            │   Domain Model & Micronaut Data     │  (lol.pbu.kaiju.domain)
            │      PostGIS / JDBC Persistence     │
            └─────────────────────────────────────┘
```

---

## 2. Build Configuration Updates (`build.gradle.kts`) (Issue #51)

### 2.1 Modifications & Rationale

1. **Pin SonarQube Gradle Plugin**:
   - *Current*: `id("org.sonarqube") version "latest.release"`
   - *Change*: `id("org.sonarqube") version "7.5.0.8588"` (or pinned via gradle property).
   - *Rationale*: Eliminates non-deterministic builds and supply-chain drift across CI environments.
2. **Remove Logback Annotation Processor**:
   - *Current*: `annotationProcessor("ch.qos.logback:logback-classic")`
   - *Change*: Remove line completely.
   - *Rationale*: `logback-classic` does not contain an annotation processor. Placing it on `annotationProcessor` configuration slows down `javac` and generates compiler warnings.
3. **Remove SnakeYAML Runtime Dependency**:
   - *Current*: `runtimeOnly("org.yaml:snakeyaml")`
   - *Change*: Remove line completely.
   - *Rationale*: When Micronaut AOT enables `convertYamlToJava = true`, application configuration is compiled directly into Java bytecode, making SnakeYAML obsolete at runtime.
4. **Enable Micronaut AOT Optimization Flags**:
   - *Current*:

     ```kotlin
     aot {
         optimizeServiceLoading = false
         convertYamlToJava = false
         ...
     }
     ```

   - *Change*:

     ```kotlin
     aot {
         optimizeServiceLoading = true
         convertYamlToJava = true
         precomputeOperations = true
         cacheEnvironment = true
         optimizeClassLoading = true
         deduceEnvironment = true
         optimizeNetty = true
         replaceLogbackXml = true
     }
     ```

   - *Rationale*:
     - `optimizeServiceLoading = true`: Precomputes `ServiceLoader` discovery at build time, eliminating reflective file lookups in `META-INF/services`.
     - `convertYamlToJava = true`: Compiles `application.yml` into Java bytecode classes, cutting startup overhead and memory allocations.

### 2.2 Gradle Diff Specification

```kotlin
// In build.gradle.kts
plugins {
    id("groovy") 
    id("io.micronaut.application") version "5.0.2"
    id("com.gradleup.shadow") version "9.4.1"
    id("io.micronaut.aot") version "5.0.2"
    id("io.micronaut.test-resources") version "5.0.2"
-   id("org.sonarqube") version "latest.release"
+   id("org.sonarqube") version "7.5.0.8588"
    id("org.asciidoctor.jvm.pdf") version "4.0.2"
    id("jacoco")
}

dependencies {
    annotationProcessor("io.micronaut.data:micronaut-data-processor")
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.security:micronaut-security-processor")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
-   annotationProcessor("ch.qos.logback:logback-classic")
    ...
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
-   runtimeOnly("org.yaml:snakeyaml")
    ...
}

micronaut {
    runtime("netty")
    testRuntime("spock2")
    processing {
        incremental(true)
        annotations("lol.pbu.*")
    }
    aot {
-       optimizeServiceLoading = false
-       convertYamlToJava = false
+       optimizeServiceLoading = true
+       convertYamlToJava = true
        precomputeOperations = true
        cacheEnvironment = true
        optimizeClassLoading = true
        deduceEnvironment = true
        optimizeNetty = true
        replaceLogbackXml = true
    }
}
```

---

## 3. Compile-Time Serde & Spatial Support (Issue #51)

### 3.1 Domain Model Serde Annotations

Every domain entity record in `lol.pbu.kaiju.domain` must be annotated with `@Serdeable`:
- `lol.pbu.kaiju.domain.AdministrativeRegion`
- `lol.pbu.kaiju.domain.Boundary`
- `lol.pbu.kaiju.domain.Location`
- `lol.pbu.kaiju.domain.Organization` (verified existing)
- `lol.pbu.kaiju.domain.OrganizationAuditLog`
- `lol.pbu.kaiju.domain.OrganizationUser`
- `lol.pbu.kaiju.domain.OrganizationUserId` (verified existing)
- `lol.pbu.kaiju.domain.Project`
- `lol.pbu.kaiju.domain.ProjectAuditLog`
- `lol.pbu.kaiju.domain.RegionUser`
- `lol.pbu.kaiju.domain.RegionUserId` (verified existing)
- `lol.pbu.kaiju.domain.Shift`
- `lol.pbu.kaiju.domain.Tag`
- `lol.pbu.kaiju.domain.User`

### 3.2 Spatial Type Serde Serialization & Deserialization

Micronaut Serde cannot introspect third-party classes from `org.locationtech.jts.geom.*` (`Point`, `Polygon`, `Geometry`) because JTS classes contain recursive object graphs (`getFactory()`, `getPrecisionModel()`, `getEnvelope()`).
To prevent serialization errors during HTTP Netty responses and requests:
1. Provide `@Singleton` Serde serializers and deserializers in `lol.pbu.kaiju.serde`:
   - `JtsPointSerde`: Implements `Serializer<Point>` and `Deserializer<Point>`.
     - Serializes `Point` to standard GeoJSON representation:

       ```json
       {"type": "Point", "coordinates": [-104.9903, 39.7392]}
       ```

     - Deserializes GeoJSON `{"type": "Point", "coordinates": [x, y]}` into a JTS `Point` (SRID 4326) via `GeometryFactory`.
   - `JtsGeometrySerde`: Implements `Serializer<Geometry>` and `Deserializer<Geometry>`.
     - Serializes `Geometry` / `Polygon` to GeoJSON polygon structure:

       ```json
       {"type": "Polygon", "coordinates": [[[-105.1, 39.8], [-104.7, 39.8], ...]]}
       ```

     - Deserializes GeoJSON coordinates into a JTS `Polygon` (SRID 4326).
2. All DTO records accepting coordinates expose typed primitive structures (`CoordinateDto` or explicit `Double longitude, Double latitude`), completely decoupling external consumers from JTS.

---

## 4. Architectural Design & Class/Record Specifications (Issues #55, #26, #41, #42)

All DTOs reside in package `lol.pbu.kaiju.dto`. They are immutable Java records annotated with `@Serdeable`.

```
lol.pbu.kaiju.dto
├── CoordinateDto
├── ProjectLocationCommand      (Issue #26: Mass-assignment immune)
├── ProjectBoundaryCommand      (Issue #26: Mass-assignment immune)
├── CreateProjectCommand        (Issue #41: @NotEmpty boundaries, Issue #42: @Size(min=20, max=4000) description)
├── UpdateProjectCommand        (Issue #41: @NotEmpty boundaries, Issue #42: @Size(min=20, max=4000) description)
├── CreateOrganizationCommand
├── UpdateOrganizationCommand
├── CreateLocationCommand
├── UpdateLocationCommand
├── CreateBoundaryCommand
├── UpdateBoundaryCommand
├── CreateAdministrativeRegionCommand
├── UpdateAdministrativeRegionCommand
├── CreateShiftCommand
├── UpdateShiftCommand
├── CreateTagCommand
├── UpdateTagCommand
├── CreateUserCommand
├── UpdateUserCommand           (Role excluded: privilege-escalation immune)
├── CreateOrganizationUserCommand
├── UpdateOrganizationUserCommand
├── CreateRegionUserCommand
├── UpdateRegionUserCommand
├── CreateOrganizationAuditLogCommand
├── UpdateOrganizationAuditLogCommand
├── CreateProjectAuditLogCommand
└── UpdateProjectAuditLogCommand
```

### 4.1 Detailed Record Specifications

#### 4.1.1 Spatial Primitives & Child Project Commands (Issue #26)

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Serdeable
public record CoordinateDto(
        @NotNull(message = "Longitude is required.")
        @Min(value = -180, message = "Longitude must be greater than or equal to -180.")
        @Max(value = 180, message = "Longitude must be less than or equal to 180.")
        Double longitude,

        @NotNull(message = "Latitude is required.")
        @Min(value = -90, message = "Latitude must be greater than or equal to -90.")
        @Max(value = 90, message = "Latitude must be less than or equal to 90.")
        Double latitude
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Child location command embedded inside Project commands.
 * CRITICAL: Contains NO 'id' field, eliminating child entity mass assignment (Issue #26).
 */
@Serdeable
public record ProjectLocationCommand(
        @NotBlank(message = "Location name is required.")
        @Size(min = 1, max = 255, message = "Location name must be between 1 and 255 characters.")
        String name,

        @NotBlank(message = "Location address line is required.")
        @Size(min = 1, max = 255, message = "Location address line must be between 1 and 255 characters.")
        String addressLine,

        @NotBlank(message = "Location city is required.")
        @Size(min = 1, max = 100, message = "Location city must be between 1 and 100 characters.")
        String city,

        @Nullable
        @Size(min = 1, max = 100, message = "Location state/province must be between 1 and 100 characters.")
        String stateProvince,

        @Nullable
        @Size(min = 1, max = 20, message = "Location postal code must be between 1 and 20 characters.")
        String postalCode,

        @NotBlank(message = "Location country code is required.")
        @Size(min = 2, max = 2, message = "Location country code must be 2 characters.")
        String countryCode,

        @NotNull(message = "Longitude is required.")
        @Min(value = -180, message = "Longitude must be greater than or equal to -180.")
        @Max(value = 180, message = "Longitude must be less than or equal to 180.")
        Double longitude,

        @NotNull(message = "Latitude is required.")
        @Min(value = -90, message = "Latitude must be greater than or equal to -90.")
        @Max(value = 90, message = "Latitude must be less than or equal to 90.")
        Double latitude
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Child boundary command embedded inside Project commands.
 * CRITICAL: Contains NO 'id' field, eliminating child entity mass assignment (Issue #26).
 */
@Serdeable
public record ProjectBoundaryCommand(
        @NotBlank(message = "Boundary name is required.")
        @Size(min = 1, max = 255, message = "Boundary name must be between 1 and 255 characters.")
        String name,

        @NotNull(message = "Boundary coordinates are required.")
        @Size(min = 3, message = "A polygon boundary must contain at least 3 coordinates.")
        @Valid
        List<CoordinateDto> coordinates
) {}
```

#### 4.1.2 Project Commands (Issues #41, #42, #26, #55)

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.model.ProjectType;

import java.util.List;
import java.util.UUID;

@Serdeable
public record CreateProjectCommand(
        @NotNull(message = "Project organization ID is required.")
        UUID organizationId,

        @Nullable
        UUID managingRegionId,

        @NotBlank(message = "Project title is required.")
        @Size(min = 1, max = 255, message = "Project title must be between 1 and 255 characters.")
        String title,

        @NotBlank(message = "Project description is required.")
        @Size(min = 20, max = 4000, message = "Project description must be between 20 and 4000 characters.")
        String description,

        @NotNull(message = "Project type is required.")
        ProjectType projectType,

        @Nullable
        ProjectStatus status,

        @Nullable
        @Valid
        List<ProjectLocationCommand> locations,

        @NotNull(message = "Project boundaries are required.")
        @NotEmpty(message = "Project boundaries must not be empty.")
        @Valid
        List<ProjectBoundaryCommand> boundaries
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.ProjectType;

import java.util.List;
import java.util.UUID;

@Serdeable
public record UpdateProjectCommand(
        @Nullable
        UUID organizationId,

        @Nullable
        UUID managingRegionId,

        @NotBlank(message = "Project title is required.")
        @Size(min = 1, max = 255, message = "Project title must be between 1 and 255 characters.")
        String title,

        @NotBlank(message = "Project description is required.")
        @Size(min = 20, max = 4000, message = "Project description must be between 20 and 4000 characters.")
        String description,

        @NotNull(message = "Project type is required.")
        ProjectType projectType,

        @Nullable
        @Valid
        List<ProjectLocationCommand> locations,

        @NotNull(message = "Project boundaries are required.")
        @NotEmpty(message = "Project boundaries must not be empty.")
        @Valid
        List<ProjectBoundaryCommand> boundaries
) {}
```

#### 4.1.3 Organization Commands

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Serdeable
public record CreateOrganizationCommand(
        @NotBlank(message = "Organization name is required.")
        @Size(min = 1, max = 255, message = "Organization name must be between 1 and 255 characters.")
        String name,

        @Nullable
        @Size(min = 1, max = 255, message = "Organization website URL must be between 1 and 255 characters.")
        String websiteUrl,

        @Nullable
        UUID parentId,

        @NotNull(message = "isPublic is required.")
        Boolean isPublic
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Serdeable
public record UpdateOrganizationCommand(
        @NotBlank(message = "Organization name is required.")
        @Size(min = 1, max = 255, message = "Organization name must be between 1 and 255 characters.")
        String name,

        @Nullable
        @Size(min = 1, max = 255, message = "Organization website URL must be between 1 and 255 characters.")
        String websiteUrl,

        @Nullable
        UUID parentId,

        @NotNull(message = "isPublic is required.")
        Boolean isPublic
) {}
```

#### 4.1.4 Location & Boundary Standalone Commands

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Serdeable
public record CreateLocationCommand(
        @NotBlank(message = "Location name is required.")
        @Size(min = 1, max = 255, message = "Location name must be between 1 and 255 characters.")
        String name,

        @NotBlank(message = "Location address line is required.")
        @Size(min = 1, max = 255, message = "Location address line must be between 1 and 255 characters.")
        String addressLine,

        @NotBlank(message = "Location city is required.")
        @Size(min = 1, max = 100, message = "Location city must be between 1 and 100 characters.")
        String city,

        @Nullable
        @Size(min = 1, max = 100, message = "Location state/province must be between 1 and 100 characters.")
        String stateProvince,

        @Nullable
        @Size(min = 1, max = 20, message = "Location postal code must be between 1 and 20 characters.")
        String postalCode,

        @NotBlank(message = "Location country code is required.")
        @Size(min = 2, max = 2, message = "Location country code must be 2 characters.")
        String countryCode,

        @NotNull(message = "Longitude is required.")
        @Min(value = -180, message = "Longitude must be greater than or equal to -180.")
        @Max(value = 180, message = "Longitude must be less than or equal to 180.")
        Double longitude,

        @NotNull(message = "Latitude is required.")
        @Min(value = -90, message = "Latitude must be greater than or equal to -90.")
        @Max(value = 90, message = "Latitude must be less than or equal to 90.")
        Double latitude
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Serdeable
public record UpdateLocationCommand(
        @NotBlank(message = "Location name is required.")
        @Size(min = 1, max = 255, message = "Location name must be between 1 and 255 characters.")
        String name,

        @NotBlank(message = "Location address line is required.")
        @Size(min = 1, max = 255, message = "Location address line must be between 1 and 255 characters.")
        String addressLine,

        @NotBlank(message = "Location city is required.")
        @Size(min = 1, max = 100, message = "Location city must be between 1 and 100 characters.")
        String city,

        @Nullable
        @Size(min = 1, max = 100, message = "Location state/province must be between 1 and 100 characters.")
        String stateProvince,

        @Nullable
        @Size(min = 1, max = 20, message = "Location postal code must be between 1 and 20 characters.")
        String postalCode,

        @NotBlank(message = "Location country code is required.")
        @Size(min = 2, max = 2, message = "Location country code must be 2 characters.")
        String countryCode,

        @NotNull(message = "Longitude is required.")
        @Min(value = -180, message = "Longitude must be greater than or equal to -180.")
        @Max(value = 180, message = "Longitude must be less than or equal to 180.")
        Double longitude,

        @NotNull(message = "Latitude is required.")
        @Min(value = -90, message = "Latitude must be greater than or equal to -90.")
        @Max(value = 90, message = "Latitude must be less than or equal to 90.")
        Double latitude
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Serdeable
public record CreateBoundaryCommand(
        @NotBlank(message = "Boundary name is required.")
        @Size(min = 1, max = 255, message = "Boundary name must be between 1 and 255 characters.")
        String name,

        @NotNull(message = "Boundary coordinates are required.")
        @Size(min = 3, message = "A polygon boundary must contain at least 3 coordinates.")
        @Valid
        List<CoordinateDto> coordinates
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Serdeable
public record UpdateBoundaryCommand(
        @NotBlank(message = "Boundary name is required.")
        @Size(min = 1, max = 255, message = "Boundary name must be between 1 and 255 characters.")
        String name,

        @NotNull(message = "Boundary coordinates are required.")
        @Size(min = 3, message = "A polygon boundary must contain at least 3 coordinates.")
        @Valid
        List<CoordinateDto> coordinates
) {}
```

#### 4.1.5 Administrative Region Commands

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Serdeable
public record CreateAdministrativeRegionCommand(
        @NotBlank(message = "Administrative region name is required.")
        @Size(min = 1, max = 255, message = "Administrative region name must be between 1 and 255 characters.")
        String name,

        @Nullable
        UUID parentRegionId,

        @NotNull(message = "Region coordinates are required.")
        @Size(min = 3, message = "A region polygon must contain at least 3 coordinates.")
        @Valid
        List<CoordinateDto> coordinates
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Serdeable
public record UpdateAdministrativeRegionCommand(
        @NotBlank(message = "Administrative region name is required.")
        @Size(min = 1, max = 255, message = "Administrative region name must be between 1 and 255 characters.")
        String name,

        @Nullable
        UUID parentRegionId,

        @NotNull(message = "Region coordinates are required.")
        @Size(min = 3, message = "A region polygon must contain at least 3 coordinates.")
        @Valid
        List<CoordinateDto> coordinates
) {}
```

#### 4.1.6 Shift Commands

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Serdeable
public record CreateShiftCommand(
        @NotNull(message = "Shift project is required.")
        UUID projectId,

        boolean isVirtual,

        @Nullable
        UUID locationId,

        @NotNull(message = "Shift start time is required.")
        OffsetDateTime startTime,

        @NotNull(message = "Shift end time is required.")
        OffsetDateTime endTime,

        @Nullable
        List<UUID> tagIds
) {
    @AssertTrue(message = "A shift must have a location if it is not virtual, and must not have a location if it is virtual.")
    public boolean isValidLocationLogic() {
        return (isVirtual && locationId == null) || (!isVirtual && locationId != null);
    }
}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Serdeable
public record UpdateShiftCommand(
        boolean isVirtual,

        @Nullable
        UUID locationId,

        @NotNull(message = "Shift start time is required.")
        OffsetDateTime startTime,

        @NotNull(message = "Shift end time is required.")
        OffsetDateTime endTime,

        @Nullable
        List<UUID> tagIds
) {
    @AssertTrue(message = "A shift must have a location if it is not virtual, and must not have a location if it is virtual.")
    public boolean isValidLocationLogic() {
        return (isVirtual && locationId == null) || (!isVirtual && locationId != null);
    }
}
```

#### 4.1.7 Tag Commands

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Serdeable
public record CreateTagCommand(
        @NotBlank(message = "Tag name is required.")
        @Size(min = 1, max = 50, message = "Tag name must be between 1 and 50 characters.")
        String name
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Serdeable
public record UpdateTagCommand(
        @NotBlank(message = "Tag name is required.")
        @Size(min = 1, max = 50, message = "Tag name must be between 1 and 50 characters.")
        String name
) {}
```

#### 4.1.8 User Commands (Privilege Escalation Prevention)

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.UserRole;

/**
 * Note: 'role' is @Nullable to guarantee registration defaults safely to STANDARD_USER.
 * Role Escalation Guard: Only callers holding SYSTEM_USER_MANAGE_CLAIM may assign an elevated role.
 */
@Serdeable
public record CreateUserCommand(
        @NotBlank(message = "User email is required.")
        @Email(message = "User email must be a valid email address.")
        @Size(min = 1, max = 255, message = "User email must be between 1 and 255 characters.")
        String email,

        @Nullable
        UserRole role
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Note: 'role' is intentionally excluded to prevent self-privilege escalation during profile update.
 * Roles are modified exclusively via AdminUserController#updateUserRole.
 */
@Serdeable
public record UpdateUserCommand(
        @NotBlank(message = "User email is required.")
        @Email(message = "User email must be a valid email address.")
        @Size(min = 1, max = 255, message = "User email must be between 1 and 255 characters.")
        String email
) {}
```

#### 4.1.9 Membership & Association Commands

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.OrganizationUserRole;
import java.util.UUID;

@Serdeable
public record CreateOrganizationUserCommand(
        @NotNull(message = "User ID is required.")
        UUID userId,

        @NotNull(message = "Organization ID is required.")
        UUID organizationId,

        @NotNull(message = "Organization user role is required.")
        OrganizationUserRole role
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.OrganizationUserRole;

@Serdeable
public record UpdateOrganizationUserCommand(
        @NotNull(message = "Organization user role is required.")
        OrganizationUserRole role
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.RegionUserRole;
import java.util.UUID;

@Serdeable
public record CreateRegionUserCommand(
        @NotNull(message = "User ID is required.")
        UUID userId,

        @NotNull(message = "Region ID is required.")
        UUID regionId,

        @NotNull(message = "Region user role is required.")
        RegionUserRole role
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.RegionUserRole;

@Serdeable
public record UpdateRegionUserCommand(
        @NotNull(message = "Region user role is required.")
        RegionUserRole role
) {}
```

#### 4.1.10 Audit Log Commands

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Serdeable
public record CreateOrganizationAuditLogCommand(
        @NotNull(message = "Organization ID is required.")
        UUID organizationId,

        @NotNull(message = "Actor ID is required.")
        UUID actorId,

        @NotBlank(message = "Previous status is required.")
        String previousStatus,

        @NotBlank(message = "New status is required.")
        String newStatus,

        @Nullable
        String reason
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

@Serdeable
public record UpdateOrganizationAuditLogCommand(
        @NotBlank(message = "Previous status is required.")
        String previousStatus,

        @NotBlank(message = "New status is required.")
        String newStatus,

        @Nullable
        String reason
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.AuditAction;
import java.util.UUID;

@Serdeable
public record CreateProjectAuditLogCommand(
        @NotNull(message = "Project ID is required.")
        UUID projectId,

        @NotNull(message = "Actor ID is required.")
        UUID actorId,

        @NotNull(message = "Audit log action is required.")
        AuditAction action
) {}
```

```java
package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.AuditAction;

@Serdeable
public record UpdateProjectAuditLogCommand(
        @NotNull(message = "Audit log action is required.")
        AuditAction action
) {}
```

---

## 5. Reflection-Free Domain Mapping Strategy

To maintain zero reflection overhead and maximum compile-time verification, mappings between DTOs and domain records are implemented via:
1. Pure Java record instance methods (`toEntity(...)`).
2. A dedicated compile-time helper `lol.pbu.kaiju.util.SpatialMappingUtils` to map `List<CoordinateDto>` to JTS `Polygon` geometries safely without reflection.

### 5.1 Singleton SpatialMappingService (Architecture Enhancement)

To avoid instantiating `new GeometryFactory()` across mappers and controllers, a dedicated `@Singleton` service is introduced that injects the application-wide `GeometryFactory` bean.

```java
package lol.pbu.kaiju.util;

import jakarta.inject.Singleton;
import lol.pbu.kaiju.dto.CoordinateDto;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class SpatialMappingService {
    private final GeometryFactory geometryFactory;

    public SpatialMappingService(GeometryFactory geometryFactory) {
        this.geometryFactory = geometryFactory;
    }

    public Point toPoint(Double longitude, Double latitude) {
        if (longitude == null || latitude == null) {
            return null;
        }
        return geometryFactory.createPoint(new Coordinate(longitude, latitude));
    }

    public Polygon toPolygon(List<CoordinateDto> dtos) {
        if (dtos == null || dtos.size() < 3) {
            throw new IllegalArgumentException("A polygon requires at least 3 coordinates.");
        }
        List<Coordinate> coords = new ArrayList<>(dtos.size() + 1);
        for (CoordinateDto dto : dtos) {
            coords.add(new Coordinate(dto.longitude(), dto.latitude()));
        }
        // Ensure polygon ring closure
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (!first.equals2D(last)) {
            coords.add(new Coordinate(first.x, first.y));
        }
        LinearRing ring = geometryFactory.createLinearRing(coords.toArray(new Coordinate[0]));
        return geometryFactory.createPolygon(ring);
    }
}
```

### 5.2 Mapping Project Command to Entity (Mass-Assignment Immune)

When creating or updating a project, child locations and boundaries are converted without client-supplied IDs:

```java
// Mapping child locations: ID is explicitly null (auto-generated)
// Mapping child locations: ID is explicitly null (auto-generated)
List<Location> domainLocations = command.locations() == null ? List.of() :
    command.locations().stream()
        .map(loc -> new Location(
            null, // Server generated ID only
            loc.name(),
            loc.addressLine(),
            loc.city(),
            loc.stateProvince(),
            loc.postalCode(),
            loc.countryCode(),
            spatialMappingService.toPoint(loc.longitude(), loc.latitude())
        ))
        .toList();

// Mapping child boundaries: ID is explicitly null (auto-generated)
List<Boundary> domainBoundaries = command.boundaries().stream()
    .map(bnd -> new Boundary(
        null, // Server generated ID only
        bnd.name(),
        spatialMappingService.toPolygon(bnd.coordinates())
    ))
    .toList();
```

---

## 6. Controller Refactoring Specification

All controllers are updated:
- Bind `@Valid @Body <CommandDto> command`.
- Remove any inline FQCNs (convert to explicit imports at file header).
- Maintain existing authorization annotations and transaction management.

### 6.1 Matrix of Refactored Endpoints

| Controller | HTTP Method | Endpoint URI | Old `@Body` Parameter | New `@Body` Parameter |
|---|---|---|---|---|
| `ProjectController` | `POST` | `/projects` | `@Valid @Body Project project` | `@Valid @Body CreateProjectCommand command` |
| `ProjectController` | `PUT` | `/projects/{id}` | `@Valid @Body Project project` | `@Valid @Body UpdateProjectCommand command` |
| `OrganizationController` | `POST` | `/organizations` | `@Valid @Body Organization organization` | `@Valid @Body CreateOrganizationCommand command` |
| `OrganizationController` | `PUT` | `/organizations/{id}` | `@Valid @Body Organization organization` | `@Valid @Body UpdateOrganizationCommand command` |
| `LocationController` | `POST` | `/locations` | `@Valid @Body Location location` | `@Valid @Body CreateLocationCommand command` |
| `LocationController` | `PUT` | `/locations/{id}` | `@Valid @Body Location location` | `@Valid @Body UpdateLocationCommand command` |
| `BoundaryController` | `POST` | `/boundaries` | `@Valid @Body Boundary boundary` | `@Valid @Body CreateBoundaryCommand command` |
| `BoundaryController` | `PUT` | `/boundaries/{id}` | `@Valid @Body Boundary boundary` | `@Valid @Body UpdateBoundaryCommand command` |
| `AdministrativeRegionController` | `POST` | `/administrative-regions` | `@Valid @Body AdministrativeRegion region` | `@Valid @Body CreateAdministrativeRegionCommand command` |
| `AdministrativeRegionController` | `PUT` | `/administrative-regions/{id}` | `@Valid @Body AdministrativeRegion region` | `@Valid @Body UpdateAdministrativeRegionCommand command` |
| `ShiftController` | `POST` | `/shifts` | `@Valid @Body Shift shift` | `@Valid @Body CreateShiftCommand command` |
| `ShiftController` | `PUT` | `/shifts/{id}` | `@Valid @Body Shift shift` | `@Valid @Body UpdateShiftCommand command` |
| `TagController` | `POST` | `/tags` | `@Valid @Body Tag tag` | `@Valid @Body CreateTagCommand command` |
| `TagController` | `PUT` | `/tags/{id}` | `@Valid @Body Tag tag` | `@Valid @Body UpdateTagCommand command` |
| `UserController` | `POST` | `/users` | `@Valid @Body User user` | `@Valid @Body CreateUserCommand command` (Role Escalation Guard enforced) |
| `UserController` | `PUT` | `/users/{id}` | `@Valid @Body User user` | `@Valid @Body UpdateUserCommand command` |
| `OrganizationUserController` | `POST` | `/organization-users` | `@Valid @Body OrganizationUser user` | `@Valid @Body CreateOrganizationUserCommand command` |
| `OrganizationUserController` | `PUT` | `/organization-users/{userId}/{organizationId}` | `@Valid @Body OrganizationUser user` | `@Valid @Body UpdateOrganizationUserCommand command` |
| `RegionUserController` | `POST` | `/region-users` | `@Valid @Body RegionUser user` | `@Valid @Body CreateRegionUserCommand command` |
| `RegionUserController` | `PUT` | `/region-users/{userId}/{regionId}` | `@Valid @Body RegionUser user` | `@Valid @Body UpdateRegionUserCommand command` |
| `OrganizationAuditLogController` | `POST` | `/organization-audit-logs` | `@Valid @Body OrganizationAuditLog log` | `@Valid @Body CreateOrganizationAuditLogCommand command` |
| `OrganizationAuditLogController` | `PUT` | `/organization-audit-logs/{id}` | `@Valid @Body OrganizationAuditLog log` | `@Valid @Body UpdateOrganizationAuditLogCommand command` |
| `ProjectAuditLogController` | `POST` | `/project-audit-logs` | `@Valid @Body ProjectAuditLog log` | `@Valid @Body CreateProjectAuditLogCommand command` |
| `ProjectAuditLogController` | `PUT` | `/project-audit-logs/{id}` | `@Valid @Body ProjectAuditLog log` | `@Valid @Body UpdateProjectAuditLogCommand command` |

### 6.2 Key Controller Logic Updates

#### `ProjectController#submitProject`

```java
@Post
@Secured(IS_AUTHENTICATED)
public Project submitProject(@Valid @Body CreateProjectCommand command, Principal principal) {
    Organization org = organizationRepository.findById(command.organizationId())
            .orElseThrow(() -> new HttpStatusException(BAD_REQUEST, "Organization is required"));

    UUID submitterId = UUID.fromString(principal.getName());
    AdministrativeRegion managingRegion = null;

    if (command.managingRegionId() != null) {
        managingRegion = administrativeRegionRepository.findById(command.managingRegionId())
                .orElseThrow(() -> new HttpStatusException(BAD_REQUEST, "Managing region does not exist"));
    }

    List<Location> domainLocations = mapLocations(command.locations());
    List<Boundary> domainBoundaries = mapBoundaries(command.boundaries());

    Project transientProject = new Project(
            null,
            org,
            managingRegion,
            command.title(),
            command.description(),
            command.projectType(),
            command.status() != null ? command.status() : ProjectStatus.DRAFT,
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            null,
            domainLocations,
            domainBoundaries
    );

    if (managingRegion != null) {
        if (!domainLocations.isEmpty()) {
            if (!securityService.areAllLocationsInRegion(transientProject, managingRegion.id())) {
                throw new HttpStatusException(BAD_REQUEST, "Project locations do not fall within the specified managing region");
            }
        }
        if (!securityService.canAssignManagingRegion(submitterId, org.id(), managingRegion.id())) {
            throw new HttpStatusException(FORBIDDEN, "You do not have authority to assign this managing region");
        }
    }

    ProjectStatus evaluatedStatus = securityService.evaluateProjectCreationByUser(submitterId, transientProject);

    Project secureProject = new Project(
            null,
            org,
            managingRegion,
            command.title(),
            command.description(),
            command.projectType(),
            evaluatedStatus,
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            null,
            domainLocations,
            domainBoundaries
    );

    return projectRepository.save(secureProject);
}
```

#### `ProjectController#updateProject`

```java
@Put("/{id}")
@Secured(IS_AUTHENTICATED)
public Project updateProject(@PathVariable UUID id, @Valid @Body UpdateProjectCommand command, Principal principal) {
    Project existing = projectRepository.findById(id)
            .orElseThrow(() -> new HttpStatusException(NOT_FOUND, PROJECT_NOT_FOUND));

    UUID userId = UUID.fromString(principal.getName());
    if (!securityService.canModifyProject(userId, existing)) {
        throw new HttpStatusException(FORBIDDEN, "You do not have permission to modify this project");
    }

    Organization targetOrg = existing.organization();
    UUID existingOrgId = existing.organization() != null ? existing.organization().id() : null;

    if (command.organizationId() != null) {
        UUID requestedOrgId = command.organizationId();
        if (!Objects.equals(requestedOrgId, existingOrgId)) {
            if (!securityService.canReassignProject(userId, existing)) {
                throw new HttpStatusException(FORBIDDEN, "You do not have permission to reassign this project to another organization");
            }
            targetOrg = organizationRepository.findById(requestedOrgId)
                    .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Target organization not found"));
        }
    }

    AdministrativeRegion targetRegion = existing.managingRegion();
    if (command.managingRegionId() != null && !Objects.equals(command.managingRegionId(), existing.managingRegion() != null ? existing.managingRegion().id() : null)) {
        targetRegion = administrativeRegionRepository.findById(command.managingRegionId())
                .orElseThrow(() -> new HttpStatusException(BAD_REQUEST, "Managing region does not exist"));
    }

    List<Location> domainLocations = mapLocations(command.locations());
    List<Boundary> domainBoundaries = mapBoundaries(command.boundaries());

    Project transientProject = new Project(
            id,
            targetOrg,
            targetRegion,
            command.title(),
            command.description(),
            command.projectType(),
            existing.status(),
            existing.createdAt(),
            existing.deletedAt(),
            existing.deletedBy(),
            domainLocations,
            domainBoundaries
    );

    if (targetRegion != null && !Objects.equals(targetRegion, existing.managingRegion())) {
        if (!domainLocations.isEmpty()) {
            if (!securityService.areAllLocationsInRegion(transientProject, targetRegion.id())) {
                throw new HttpStatusException(BAD_REQUEST, "Project locations do not fall within the specified managing region");
            }
        }
        UUID effectiveOrgId = targetOrg != null ? targetOrg.id() : existingOrgId;
        if (effectiveOrgId == null || !securityService.canAssignManagingRegion(userId, effectiveOrgId, targetRegion.id())) {
            throw new HttpStatusException(FORBIDDEN, "You do not have authority to assign this managing region");
        }
    }

    ProjectStatus newStatus = existing.status();
    boolean locationsModified = !Objects.equals(domainLocations, existing.locations());
    boolean reassigned = !Objects.equals(targetOrg != null ? targetOrg.id() : null, existingOrgId);

    if (locationsModified || reassigned) {
        if (existing.status() == ACTIVE && (targetOrg == null || !securityService.areAllLocationsInOrgRegion(transientProject, targetOrg.id()))) {
            newStatus = PENDING;
        }
    }

    Project secureProject = new Project(
            id,
            targetOrg,
            targetRegion,
            command.title(),
            command.description(),
            command.projectType(),
            newStatus,
            existing.createdAt(),
            existing.deletedAt(),
            existing.deletedBy(),
            domainLocations,
            domainBoundaries
    );

    return projectRepository.update(secureProject);
}
```

---

#### `UserController#addUser` (Role Escalation Guard - Amendment 2)

```java
@Post
public User addUser(@Valid @Body CreateUserCommand command, @Nullable Principal principal) {
    UserRole assignedRole = UserRole.STANDARD_USER;
    if (command.role() != null && command.role() != UserRole.STANDARD_USER) {
        if (principal == null) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: Only administrators can assign elevated user roles.");
        }
        UUID callerId = UUID.fromString(principal.getName());
        boolean hasAdminManage = userRepository.findById(callerId)
                .map(u -> u.role().hasPermission(Permission.SYSTEM_USER_MANAGE) || u.role().hasPermission(Permission.SYSTEM_ADMIN))
                .orElse(false);
        if (!hasAdminManage) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: Only administrators can assign elevated user roles.");
        }
        assignedRole = command.role();
    }

    User newUser = new User(
            null,
            command.email(),
            assignedRole,
            OffsetDateTime.now(ZoneOffset.UTC)
    );
    return userRepository.save(newUser);
}
```

---

## 7. Comprehensive Testing & Verification Plan (Issue #52)

### 7.1 Infrastructure: Base HTTP Pipeline Spec

To test the real Netty HTTP pipeline, `BaseControllerSpec` is upgraded to provide:
1. `@Client("/") HttpClient httpClient` with `BlockingHttpClient getClient()`.
2. Seamless test authentication via `TestAuthenticationFetcher` in test mode (`X-Test-User` and `X-Test-Role` request headers).
3. Helper methods to construct authenticated `MutableHttpRequest<?>` objects.

```groovy
package lol.pbu.kaiju.controller

import groovy.sql.Sql
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.publisher.Publishers
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.filters.AuthenticationFetcher
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.reactivestreams.Publisher
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
abstract class BaseControllerSpec extends Specification {

    @Singleton
    static class HeaderAuthenticationFetcher implements AuthenticationFetcher<HttpRequest<?>> {
        @Override
        Publisher<Authentication> fetchAuthentication(HttpRequest<?> request) {
            String testUser = request.getHeaders().get("X-Test-User")
            if (testUser) {
                List<String> roles = request.getHeaders().getAll("X-Test-Role")
                return Publishers.just(Authentication.build(testUser, roles))
            }
            return Publishers.empty()
        }
    }

    @Inject
    @Client("/")
    HttpClient httpClient

    BlockingHttpClient getClient() {
        httpClient.toBlocking()
    }

    @Inject
    @Shared
    DataSource dataSource

    Sql getSql() {
        new Sql(dataSource)
    }

    protected void executeUpdate(String sqlString, Object... parameters) {
        sql.execute(sqlString, parameters as List)
    }

    protected <T> MutableHttpRequest<T> authenticated(MutableHttpRequest<T> request, String userId, List<String> roles) {
        request.header("X-Test-User", userId)
        roles.each { role -> request.header("X-Test-Role", role) }
        return request
    }

    protected <T> MutableHttpRequest<T> asGlobalAdmin(MutableHttpRequest<T> request, String userId = "00000000-0000-0000-0000-000000000000") {
        authenticated(request, userId, ["GLOBAL_ADMIN", "system:admin", "project:approve", "project:manage"])
    }
}
```

### 7.2 Migrated Specifications & Coverage Matrix

Every controller spec in `src/test/groovy/lol/pbu/kaiju/controller/` must be migrated from direct method calling to HTTP pipeline tests:

1. `ProjectControllerSpec`:
   - Valid project submission via `POST /projects` returning `200 OK` / `201 Created` with generated UUID.
   - Real Jackson deserialization of response into `Project` record.
   - **Bean Validation (Issue #41)**: Submit `boundaries = []` or `null` -> Expect `400 Bad Request`.
   - **Bean Validation (Issue #42)**: Submit `description` with length 19 chars -> Expect `400 Bad Request`.
   - **Bean Validation (Issue #42)**: Submit `description` with length 4001 chars -> Expect `400 Bad Request`.
   - **Coordinate Bounds Validation (Amendment 1)**: Submit `longitude = 181.0` or `latitude = -91.0` in `CoordinateDto` / `ProjectLocationCommand` -> Expect `400 Bad Request`.
   - **Mass Assignment (Issue #26)**: Verify child `ProjectLocationCommand` and `ProjectBoundaryCommand` do not accept ID, and resulting records in `locations` and `boundaries` receive freshly generated database UUIDs.
   - **Negative Auth**: Unauthenticated request to `POST /projects` -> Expect `401 Unauthorized`.
   - **Security Rules**: Submitter without managing region authority -> Expect `403 Forbidden`.
   - **Approval Endpoint**: Regional Admin approval via `PUT /projects/{id}/status` -> Expect `200 OK` for authorized admin, `403 Forbidden` if out of geographic jurisdiction.

2. `UserControllerSpec`:
   - `POST /users` with default/omitted role -> Successfully creates user with `role = STANDARD_USER` (`200 OK`).
   - **Role Escalation Guard (Amendment 2)**: Unauthenticated or standard user submits `role = GLOBAL_ADMIN` on `POST /users` -> Expect `403 Forbidden`.
   - Admin user (with `SYSTEM_USER_MANAGE` claim) submits `role = REGION_DIRECTOR` on `POST /users` -> Expect `200 OK` with elevated role granted.
   - `PUT /users/{id}` via `UpdateUserCommand` -> Expect email update, role untouched.
   - IDOR check: Standard user A updating User B -> Expect `403 Forbidden`.

3. `LocationControllerSpec`:
   - `POST /locations` via `CreateLocationCommand` with coordinates -> Real Serde roundtrip.
   - `PUT /locations/{id}` via `UpdateLocationCommand`.
   - Invalid postal code / country code length -> Expect `400 Bad Request`.

4. `BoundaryControllerSpec`:
   - `POST /boundaries` with `< 3` coordinates -> Expect `400 Bad Request`.
   - `POST /boundaries` with valid polygon coordinates -> Expect `200 OK`.

5. `OrganizationControllerSpec`:
   - `POST /organizations` with `CreateOrganizationCommand` -> Sets `verificationStatus = UNVERIFIED`.
   - Search endpoints with query params -> `200 OK`.
   - Non-admin listing organizations -> `403 Forbidden`.

6. `ShiftControllerSpec`:
   - `POST /shifts` with `isVirtual = true` and `locationId != null` -> Cross-field `@AssertTrue` fails -> Expect `400 Bad Request`.
   - `POST /shifts` with `isVirtual = false` and `locationId == null` -> Expect `400 Bad Request`.
   - Valid virtual shift (`isVirtual = true, locationId = null`) -> Expect `200 OK`.

7. `AdministrativeRegionControllerSpec`, `TagControllerSpec`, `OrganizationUserControllerSpec`, `RegionUserControllerSpec`, `OrganizationAuditLogControllerSpec`, `ProjectAuditLogControllerSpec`:
   - Full migration of all CRUD endpoints to HTTP calls via `client.exchange(...)`.

---

## 8. Step-by-Step Implementation Strategy

```
Phase 1: Build & Config (build.gradle.kts)
   │
   ▼
Phase 2: Spatial Serde Serializers (JtsPointSerde, JtsGeometrySerde)
   │
   ▼
Phase 3: Domain Entity @Serdeable Auditing & Domain @Size(20, 4000)
   │
   ▼
Phase 4: DTO & Command Records in lol.pbu.kaiju.dto
   │
   ▼
Phase 5: Controller Refactoring across all 13 REST Controllers
   │
   ▼
Phase 6: BaseControllerSpec & HTTP Pipeline Infrastructure
   │
   ▼
Phase 7: Test Suite Migration (Spock Specs to @Client & BlockingHttpClient)
   │
   ▼
Phase 8: Verification Gates, JaCoCo, SonarQube & Lighthouse
```

### Phase 1: Build & Tooling Optimization

1. Modify `build.gradle.kts`:
   - Pin `org.sonarqube` plugin to `7.5.0.8588`.
   - Remove `annotationProcessor("ch.qos.logback:logback-classic")`.
   - Remove `runtimeOnly("org.yaml:snakeyaml")`.
   - Set `convertYamlToJava = true` and `optimizeServiceLoading = true` in `aot { ... }`.
2. Run `./gradlew compileJava` to confirm clean build configuration.

### Phase 2: Spatial Serde Serializers

1. Create `lol.pbu.kaiju.serde.JtsPointSerde` and `lol.pbu.kaiju.serde.JtsGeometrySerde`.
2. Add unit tests in `JtsConverterSpec.groovy` validating JSON string serialization and deserialization without reflection.

### Phase 3: Domain Model @Serdeable & Constraints

1. Annotate all un-annotated entities in `lol.pbu.kaiju.domain` with `@Serdeable`:
   - `AdministrativeRegion`, `Boundary`, `Location`, `OrganizationAuditLog`, `OrganizationUser`, `Project`, `ProjectAuditLog`, `RegionUser`, `Shift`, `Tag`, `User`.
2. Update `Project.java`:
   - Add `@Size(min = 20, max = 4000, message = "Project description must be between 20 and 4000 characters.")` on `description`.
   - Remove any wildcard imports (`import io.micronaut.data.annotation.*;` -> explicit imports).

### Phase 4: Command & DTO Records Creation

1. Create package directory `src/main/java/lol/pbu/kaiju/dto`.
2. Implement all 26 Command and DTO records specified in Section 4.
3. Create `SpatialMappingUtils.java` in `lol.pbu.kaiju.util`.
4. Compile with `./gradlew compileJava` to ensure Micronaut validation processor generates validator metadata without errors.

### Phase 5: Controller Refactoring

1. Update each of the 13 controllers to accept Command DTOs:
   - Replace entity parameters with Command DTOs.
   - Replace manual entity assembly with direct `toEntity` or explicit mapping calls.
   - Eliminate any inline FQCNs.
2. Compile and verify with `./gradlew compileJava`.

### Phase 6: HTTP Testing Infrastructure

1. Refactor `BaseControllerSpec.groovy` to configure `@Client("/") HttpClient httpClient`, `getClient()`, and `HeaderAuthenticationFetcher`.
2. Update `TestFixtures.java` to support creating command DTOs alongside domain test fixtures.

### Phase 7: Spock Specification Migration

1. Migrate specs sequentially:
   - `ProjectControllerSpec.groovy` (Issues #41, #42, #26, #52)
   - `OrganizationControllerSpec.groovy`
   - `UserControllerSpec.groovy`
   - `LocationControllerSpec.groovy`
   - `BoundaryControllerSpec.groovy`
   - `ShiftControllerSpec.groovy`
   - Remaining audit log, tag, and region specs.
2. Replace all controller direct method calls with `client.exchange(...)`.
3. Add specific test cases for:
   - Missing boundaries (400 Bad Request).
   - Description < 20 characters (400 Bad Request).
   - Description > 4000 characters (400 Bad Request).
   - Missing authentication (401 Unauthorized).
   - Unauthorized access (403 Forbidden).

### Phase 8: Verification & Quality Gates

1. Run `./gradlew check`.
2. Verify JaCoCo code coverage report (`build/reports/jacoco/test/html/index.html`).
3. Run `./gradlew lighthouse` markdown documentation audit.

---

## 9. Risk Analysis & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Micronaut AOT YAML Conversion Failure**: `convertYamlToJava = true` fails if dynamic YAML syntax (e.g. unquoted colons or complex SpEL expressions) is present. | Medium | High | Validate `application.yml` and `application-test.yml` syntax prior to enabling. Run `./gradlew build` in CI to verify generated Java configuration classes. |
| **Netty JTS Serde Failure**: External consumers receiving `Location` or `Project` receive serialization error due to JTS `Point` or `Geometry`. | High | High | Implement dedicated `Serializer<Point>` and `Serializer<Geometry>` beans registered as `@Singleton` serde components. Test via full `@Client` roundtrip in Spock. |
| **Existing Test Breakage on Project Description Size**: Older tests using dummy descriptions like `"Desc"` (< 20 chars) fail with 400. | High | Medium | Audit all test fixtures (`TestFixtures.java`, Groovy specs) and update dummy project descriptions to standard compliant test text (e.g. `"Test Project Description with more than 20 characters"`). |
| **Existing Test Breakage on Empty Boundaries**: Tests creating projects with `boundaries = []` fail with 400. | High | Medium | Update test fixtures to supply at least one valid boundary polygon whenever creating a project, matching the new business rule. |
| **Security Filter Bypass in Direct Method Tests**: Any remaining direct controller calls will not execute Micronaut security interceptors. | Low | High | Strictly enforce that all controller specs use `client.exchange(...)` exclusively. Delete all `@Inject <Controller>` references from controller specs. |

---

## 10. Rollback Strategy & Verification Gates

### 10.1 Rollback Strategy

1. **Branch Isolation**: All changes are developed on release branch `release/0.1.2`.
2. **Atomic Commits**: Separate commits for:
   - Commit 1: Build script updates and SonarQube pinning (#51).
   - Commit 2: Spatial Serde serializers & Domain model `@Serdeable` (#51).
   - Commit 3: DTO records in `lol.pbu.kaiju.dto` (#55, #26, #41, #42).
   - Commit 4: Controller refactoring (#55).
   - Commit 5: Spock test migration to `@Client` (#52).
3. **Rollback Trigger**: If `./gradlew check` encounters irreconcilable AOT bytecode generation issues on Java 25, revert Commit 1 AOT flags (`convertYamlToJava = false`) while retaining DTOs, controllers, and Serde annotations.

### 10.2 Verification Gates

Before merging Release 0.1.2 into `main`:
1. **Compile Gate**: `./gradlew compileJava compileTestGroovy` succeeds with zero errors and zero deprecation warnings.
2. **AOT Gate**: `./gradlew check` compiles and executes with `convertYamlToJava = true` and `optimizeServiceLoading = true`.
3. **Test Suite Gate**: All controller tests execute over Netty HTTP pipeline with 100% pass rate.
4. **Validation Gate**: 400 Bad Request verified for:
   - Description < 20 chars.
   - Boundaries empty list `[]`.
   - Invalid shift location combinations.
5. **Security Gate**: 401 Unauthorized and 403 Forbidden verified via `HttpClientResponseException` on protected endpoints.
6. **Documentation Gate**: `./gradlew lighthouse` completes successfully with link integrity verified.
