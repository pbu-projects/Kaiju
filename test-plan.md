# Test Plan: Discrete Permissions & Security Refactor

This test plan outlines the scenarios required to validate the migration to the Discrete Permissions Strategy (RBAC) and the resolution of the security vulnerabilities identified in the adversarial review.

## 1. Type Safety, Domain Model Integrity & Privilege Escalation
**Objective:** Ensure roles and permissions cannot be arbitrarily assigned or escalated via API payloads.
- [ ] **Invalid Role Validation:** Submit a request with an invalid role string (e.g., `{"role": "SUPER_HACKER"}`).
  - **Expected:** `400 Bad Request` before hitting the database.
- [ ] **Valid Role Self-Escalation:** A `STANDARD_USER` submits a profile update payload setting their role to a valid but higher role (e.g., `{"role": "GLOBAL_ADMIN"}`).
  - **Expected:** The role field is ignored (safe update) or the request is rejected with `403 Forbidden`.
- [ ] **Organization Role Escalation:** An `ORG_MEMBER` attempts to update their organization mapping to `ORG_ADMIN`, or attempts to edit the Organization profile.
  - **Expected:** `403 Forbidden`.

## 2. Discrete Permissions (Layer 1 Security) & Global Admin Rights
**Objective:** Verify that endpoints are guarded by permissions (e.g., `project:approve`), and that `GLOBAL_ADMIN` has unrestricted access as per business requirements.
- [ ] **Missing Permission:** A `STANDARD_USER` attempts to approve a project.
  - **Expected:** `403 Forbidden`.
- [ ] **Global Admin Full Access:** A `GLOBAL_ADMIN` attempts to approve, edit, and delete any project (including projects created by others or outside any specific jurisdiction).
  - **Expected:** `200 OK` (Global Admins hold all permissions).
- [ ] **Auditor Role (Read-Only):** An `AUDITOR` (read-only) attempts to view a project (`200 OK`) and attempts to edit it (`403 Forbidden`).

## 3. Horizontal Access Controls (IDOR)
**Objective:** Ensure users cannot access or modify resources belonging to other users on the same permission level.
- [ ] **Cross-User Project Modification:** `STANDARD_USER_A` attempts to modify or delete a project created by `STANDARD_USER_B`.
  - **Expected:** `403 Forbidden`.
- [ ] **Cross-User Shift Management:** `STANDARD_USER_A` attempts to view, cancel, or modify volunteer shifts belonging to `STANDARD_USER_B`.
  - **Expected:** `403 Forbidden`.

## 4. Contextual Jurisdiction (Layer 2 Security)
**Objective:** Ensure users cannot act outside their geographic or organizational boundaries, even if they possess the correct base permissions.
- [ ] **Org Spoofing:** Authenticate as a user and submit a project for an `Organization` they do not belong to.
  - **Expected:** `403 Forbidden`.
- [ ] **Out-of-Bounds Creation:** A `REGION_AGENT` attempts to create a project whose locations fall outside their assigned jurisdiction.
  - **Expected:** `403 Forbidden`.
- [ ] **Location Modification Loophole:** An `ORG_MANAGER` creates a project within their boundary (status `ACTIVE`). They then submit a `PUT` to move the locations outside their boundary.
  - **Expected:** Project status is demoted to `PENDING` or update is rejected.
- [ ] **Out-of-Bounds Approval:** A `REGION_AGENT` attempts to approve a `PENDING` project outside their jurisdiction.
  - **Expected:** `403 Forbidden`.
- [ ] **Inherited Regional Access:** A `REGION_DIRECTOR` attempts to edit a project inside their region (`200 OK`) and outside their region (`403 Forbidden`).

## 5. Virtual Projects (Zero Locations)
**Objective:** Ensure projects with no physical locations are properly managed and not subjected to flawed geographic SQL checks.
- [ ] **Global/Director Approval of Virtual Project:** A `GLOBAL_ADMIN` or properly scoped `REGION_DIRECTOR` approves a virtual project.
  - **Expected:** `200 OK`.
- [ ] **Local Agent Rejection on Virtual Project:** A geographically-bound `REGION_AGENT` attempts to approve a virtual project (which lacks geography).
  - **Expected:** `403 Forbidden` (They do not have global jurisdiction).

## 6. Lifecycle Actions (Deletions)
**Objective:** Verify deletion rules based on project state.
- [ ] **Delete Pending Project:** A `STANDARD_USER` creator deletes their own `PENDING` project.
  - **Expected:** `200 OK`.
- [ ] **Delete Active Project (Creator):** A `STANDARD_USER` creator attempts to delete their `ACTIVE` project that has volunteer signups.
  - **Expected:** `403 Forbidden` or a specific business rule error.
- [ ] **Delete Active Project (Admin):** A `GLOBAL_ADMIN` deletes an `ACTIVE` project.
  - **Expected:** `200 OK`.


## 7. Organization & Regional Role Distinctions (Vertical IDOR)
**Objective:** Verify granular access differences between levels of administration within the same hierarchy.
- [ ] **Regional Deletions:** A `REGION_AGENT` attempts to delete a project in their region (`403 Forbidden`). A `REGION_DIRECTOR` attempts the same (`200 OK`).
- [ ] **Org Verification:** A `REGION_AGENT` attempts to verify an Organization (`403 Forbidden`). A `REGION_DIRECTOR` attempts to verify an Organization in their region (`200 OK`).
- [ ] **Org Sponsor Limitations:** An `ORG_SPONSOR` attempts to edit project parameters (`403 Forbidden`), but can manage volunteers (`200 OK`).

## 8. Cross-Organization IDOR (Horizontal Access)
**Objective:** Ensure organizational boundaries are strictly enforced for organization-level administrators.
- [ ] **Cross-Org Modification:** An `ORG_ADMIN` for "Org A" attempts to edit the profile or manage users for "Org B".
  - **Expected:** `403 Forbidden`.

## 9. Untested Business Workflows & Edge Cases
**Objective:** Ensure remaining functional workflows are secured according to the matrix.
- [ ] **Virtual Project Geography Injection:** An attacker attempts to add geographic locations to an already `ACTIVE` virtual project.
  - **Expected:** Update is rejected, or the project is demoted to `PENDING` for regional review.
- [ ] **Project Reassignment:** A `STANDARD_USER` attempts to reassign a project's ownership.
  - **Expected:** `403 Forbidden` (Only applicable admins can reassign).
- [ ] **User Management/Banning:** A `REGION_DIRECTOR` attempts to ban or delete a user (`403 Forbidden`). A `GLOBAL_ADMIN` attempts the same (`200 OK`).
