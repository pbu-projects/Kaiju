# MVP Roadmap

Goal: Launch a fast, localized volunteer management platform with spatial search and moderation tooling.

## Phase 1: Spatial Foundation & Core Data

- [x] **Application Infrastructure**: Single-module Micronaut 5 + Java 25 application.
- [x] **PostGIS Schema**: Tables for `projects`, `shifts`, `locations`, `boundaries`, `organizations`, and audit logs.
- [x] **Authentication & RBAC**: Micronaut Security JWT configuration with Authentik role enforcement.
- [x] **REST Endpoints**: CRUD controllers for all domain entities.
- [x] **Data Initialization**: Database seed scripts for local development and testing.

## Phase 2: Public Web & Spatial Explorer

- [ ] **JTE View Architecture**: Server-rendered layouts for project listings and organization profiles.
- [ ] **Spatial Search Interface**: Location-based radius search (`ST_DWithin`) supporting standard, open-door, and regional projects.
- [ ] **Local Geo-Detection**: Edge header detection to suggest local projects based on user location.

## Phase 3: Organization Admin & Moderation

- [ ] **Organization Onboarding**: Self-service organization creation and project ownership workflows.
- [ ] **Regional Moderation Queue**: Admin interface to review and approve projects assigned to geographic boundaries.
- [ ] **Shift Management**: Scheduling, volunteer capacity limits, and sign-up flows.

## Phase 4: Multiplatform Mobile & Dashboards

- [ ] **Compose Multiplatform UI**: Shared interface for web admin dashboards and mobile clients (`androidApp` / `iosApp`).
- [ ] **Volunteer Profiles**: Saved preferences, shift commitments, and check-in workflows.
- [ ] **Notifications**: Shift reminders and schedule updates.
