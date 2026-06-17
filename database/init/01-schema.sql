CREATE EXTENSION IF NOT EXISTS postgis;
CREATE TABLE users
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) UNIQUE NOT NULL,
    role       VARCHAR(50)         NOT NULL CHECK (role IN ('SUPER_ADMIN', 'MODERATOR', 'ORGANIZATION_LEADER', 'VOLUNTEER')),
    created_at TIMESTAMPTZ      DEFAULT NOW()
);

CREATE TABLE organizations
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    website_url VARCHAR(255),
    parent_id   UUID REFERENCES organizations (id) ON DELETE RESTRICT
);

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
    location_type   VARCHAR(50) NOT NULL DEFAULT 'HEADQUARTERS' CHECK ( location_type in ('HEADQUARTERS', 'BRANCH') ),
    is_public       BOOLEAN              DEFAULT true,
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
    id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations (id),
    title           VARCHAR(255) NOT NULL,
    description     TEXT         NOT NULL,
    project_type    VARCHAR(50)  NOT NULL
        CHECK (project_type IN ('STANDARD', 'OPEN_DOOR', 'REGIONAL')),
    status          VARCHAR(50)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING', 'ACTIVE', 'FLAGGED', 'REJECTED')),
    created_at      TIMESTAMPTZ           DEFAULT NOW(),
       deleted_at      TIMESTAMPTZ,
    deleted_by      UUID REFERENCES users (id)
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

CREATE TABLE shifts
(
    id          UUID PRIMARY KEY                  DEFAULT gen_random_uuid(),
    project_id  UUID                     NOT NULL REFERENCES projects (id),
       is_virtual  BOOLEAN                  NOT NULL DEFAULT false,
       location_id UUID REFERENCES locations (id),
    start_time  TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time    TIMESTAMP WITH TIME ZONE NOT NULL,
       CONSTRAINT enforce_virtual_location_logic CHECK (
        (is_virtual = true AND location_id IS NULL)
            OR
        (is_virtual = false AND location_id IS NOT NULL)
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

CREATE TABLE project_audit_logs
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    actor_id   UUID        NOT NULL REFERENCES users (id),
    action     VARCHAR(50) NOT NULL
        CHECK (action IN ('CREATED', 'APPROVED', 'REJECTED', 'EDITED')),
    created_at TIMESTAMPTZ      DEFAULT NOW()
);

CREATE INDEX idx_locations_geom ON locations USING GIST (geom);
CREATE INDEX idx_shifts_pagination ON shifts (start_time, id);
CREATE INDEX idx_boundaries_geom ON boundaries USING GIST (geom);
