CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE organizations
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    website_url VARCHAR(255)
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

CREATE TABLE projects
(
    id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    title           VARCHAR(255) NOT NULL,
    description     TEXT         NOT NULL,
    status          VARCHAR(50)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING', 'ACTIVE', 'FLAGGED', 'REJECTED')),
    created_at      TIMESTAMPTZ           DEFAULT NOW()
);

CREATE TABLE project_locations
(
    project_id  UUID REFERENCES projects (id) ON DELETE CASCADE,
    location_id UUID REFERENCES locations (id) ON DELETE CASCADE,
    PRIMARY KEY (project_id, location_id)
);

CREATE TABLE shifts
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID NOT NULL,
    location_id UUID NOT NULL,
    role_title  VARCHAR(100),
    start_time  TIMESTAMPTZ,
    end_time    TIMESTAMPTZ,
    capacity    INT  NOT NULL    DEFAULT 1,

    FOREIGN KEY (project_id, location_id) REFERENCES project_locations (project_id, location_id) ON DELETE CASCADE
);

CREATE INDEX idx_locations_geom ON locations USING GIST (geom);
CREATE INDEX idx_shifts_start_time ON shifts (start_time);