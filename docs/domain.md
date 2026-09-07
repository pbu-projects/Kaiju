# Domain & Security

Core business logic, security model, and moderation flows.

## Security & RBAC

Authentication is handled by [authentik](https://goauthentik.io/), which issues JSON Web Tokens (JWTs). Micronaut Security verifies tokens and enforces role-based authorization via `@Secured`.

### Role Hierarchy

- **Platform Roles** (`users.role`):
  - `GLOBAL_ADMIN`: Full system access.
  - `REGION_DIRECTOR`: Administrative control over specific geographic territories.
  - `REGION_AGENT`: Reviews and approves regional project queues.
  - `STANDARD_USER`: Standard user.
- **Organization Roles** (`organization_users.role`):
  - `ORG_ADMIN`: Manages organization profile, members, and projects.
  - `ORG_MANAGER`: Creates and edits projects and shifts.
  - `ORG_MEMBER`: Read/view access to organization internal data.

## Workspaces & Organizations

Every project belongs to an organization (`projects.organization_id` is required). When an individual user creates a project without an organization, the system creates a personal workspace organization and assigns them `ORG_ADMIN`.

## Project Moderation & Escalation Queue

1. **Auto-Approval**: Projects submitted by organizations with `verification_status = 'VERIFIED'` are automatically active.
2. **Regional Routing**: Unverified projects enter a moderation queue assigned to their spatial region (`managing_region_id`) determined by PostGIS boundary intersection.
3. **Escalation**: If unreviewed for 48 hours, background jobs escalate the project to the parent region.
4. **Re-Verification on Edit**: Updates to active projects by non-admin users flip status back to `PENDING_UPDATE` for review.

## Audit Logging

High-liability state changes (organization verification, project approval, permission changes) record immutable entries in audit tables (`organization_audit_logs`, `project_audit_logs`) capturing the actor, timestamp, previous status, and new status within the same database transaction.
