CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE users
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) UNIQUE NOT NULL,
    role       VARCHAR(50)         NOT NULL CHECK (
        role IN (
                 'GLOBAL_ADMIN',
                 'REGION_DIRECTOR',
                 'REGION_AGENT',
                 'STANDARD_USER'
            )
        ),
    created_at TIMESTAMPTZ      DEFAULT NOW()
);

-- 2. ORGANIZATIONS & WORKSPACES
CREATE TABLE organizations
(
    id                      UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    name                    VARCHAR(255) NOT NULL,
    website_url             VARCHAR(255),
    parent_id               UUID REFERENCES organizations (id) ON DELETE RESTRICT,
    is_public               BOOLEAN               DEFAULT TRUE,
    verification_status     VARCHAR(50)  NOT NULL DEFAULT 'UNVERIFIED' CHECK (
        verification_status IN (
                                'UNVERIFIED',
                                'PENDING_REVIEW',
                                'VERIFIED',
                                'SUSPENDED',
                                'REVOKED'
            )
        ),
    verification_expires_at TIMESTAMPTZ
);

CREATE TABLE organization_users
(
    user_id         UUID REFERENCES users (id) ON DELETE CASCADE,
    organization_id UUID REFERENCES organizations (id) ON DELETE CASCADE,
    role            VARCHAR(50) NOT NULL CHECK (
        role IN ('ORG_ADMIN', 'ORG_MANAGER', 'ORG_MEMBER')
        ),
    PRIMARY KEY (user_id, organization_id)
);

CREATE TABLE organization_audit_logs
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    actor_id        UUID        NOT NULL REFERENCES users (id),
    previous_status VARCHAR(50) NOT NULL,
    new_status      VARCHAR(50) NOT NULL,
    reason          TEXT,
    created_at      TIMESTAMPTZ      DEFAULT NOW()
);

-- The Escalation Queue
CREATE TABLE administrative_regions
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(255)             NOT NULL,
    parent_region_id UUID REFERENCES administrative_regions (id),
    geom             GEOGRAPHY(Polygon, 4326) NOT NULL
);

-- 6. admin boundaries
CREATE TABLE region_users
(
    user_id   UUID REFERENCES users (id) ON DELETE CASCADE,
    region_id UUID REFERENCES administrative_regions (id) ON DELETE CASCADE,
    role      VARCHAR(50) NOT NULL CHECK (
        role IN ('REGION_DIRECTOR', 'REGION_AGENT')
        ),
    PRIMARY KEY (user_id, region_id)
);

-- 7. pizza deliveries
CREATE TABLE locations
(
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(255)           NOT NULL,
    address_line   VARCHAR(255)           NOT NULL,
    city           VARCHAR(100)           NOT NULL,
    state_province VARCHAR(100),
    postal_code    VARCHAR(20),
    country_code   CHAR(2)                NOT NULL,
    geom           GEOGRAPHY(Point, 4326) NOT NULL
);

CREATE TABLE organization_locations
(
    organization_id UUID REFERENCES organizations (id) ON DELETE CASCADE,
    location_id     UUID REFERENCES locations (id) ON DELETE CASCADE,
    PRIMARY KEY (organization_id, location_id)
);

CREATE TABLE boundaries
(
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255)             NOT NULL,
    geom GEOGRAPHY(Polygon, 4326) NOT NULL
);

CREATE TABLE projects
(
    id                 UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id    UUID         NOT NULL REFERENCES organizations (id),
    managing_region_id UUID REFERENCES administrative_regions (id), -- Used for the geographic queue
    title              VARCHAR(255) NOT NULL,
    description        TEXT         NOT NULL,
    project_type       VARCHAR(50)  NOT NULL CHECK (
        project_type IN ('STANDARD', 'OPEN_DOOR', 'REGIONAL')
        ),
    status             VARCHAR(50)  NOT NULL DEFAULT 'DRAFT' CHECK (
        status IN (
                   'DRAFT',
                   'PENDING',
                   'PENDING_UPDATE', -- Added for contextual re-approvals
                   'ACTIVE',
                   'FLAGGED',
                   'REJECTED'
            )
        ),
    created_at         TIMESTAMPTZ           DEFAULT NOW(),
    deleted_at         TIMESTAMPTZ,
    deleted_by         UUID REFERENCES users (id)
);

CREATE TABLE project_locations
(
    project_id  UUID REFERENCES projects (id) ON DELETE CASCADE,
    location_id UUID REFERENCES locations (id) ON DELETE CASCADE,
    PRIMARY KEY (project_id, location_id)
);

CREATE TABLE project_boundaries
(
    project_id  UUID REFERENCES projects (id) ON DELETE CASCADE,
    boundary_id UUID REFERENCES boundaries (id) ON DELETE CASCADE,
    PRIMARY KEY (project_id, boundary_id)
);

-- 9. SHIFTS & TAGS
CREATE TABLE shifts
(
    id          UUID PRIMARY KEY                  DEFAULT gen_random_uuid(),
    project_id  UUID                     NOT NULL REFERENCES projects (id),
    is_virtual  BOOLEAN                  NOT NULL DEFAULT FALSE,
    location_id UUID REFERENCES locations (id),
    start_time  TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT enforce_virtual_location_logic CHECK (
        (
            is_virtual = TRUE
                AND location_id IS NULL
            )
            OR (
            is_virtual = FALSE
                AND location_id IS NOT NULL
            )
        )
);

CREATE TABLE tags
(
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(50) UNIQUE NOT NULL
);

CREATE TABLE shift_tags
(
    shift_id UUID REFERENCES shifts (id) ON DELETE CASCADE,
    tag_id   UUID REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (shift_id, tag_id)
);

-- 10. PROJECT AUDIT LOGS
CREATE TABLE project_audit_logs
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    actor_id   UUID        NOT NULL REFERENCES users (id),
    action     VARCHAR(50) NOT NULL CHECK (
        action IN ('CREATED', 'APPROVED', 'REJECTED', 'EDITED')
        ),
    created_at TIMESTAMPTZ      DEFAULT NOW()
);

CREATE INDEX idx_locations_geom ON locations USING GIST (geom);
CREATE INDEX idx_shifts_pagination ON shifts (start_time, id);
CREATE INDEX idx_boundaries_geom ON boundaries USING GIST (geom);
CREATE INDEX idx_admin_regions_geom ON administrative_regions USING GIST (geom);
CREATE INDEX idx_org_users_lookup ON organization_users (user_id, organization_id);