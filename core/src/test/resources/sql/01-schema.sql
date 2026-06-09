-- copy and paste this from root/database/init

CREATE TABLE locations
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255),
    address_line VARCHAR(255)           NOT NULL,
    city         VARCHAR(100)           NOT NULL,
    state        VARCHAR(50)            NOT NULL,
    zip_code     VARCHAR(20),
    geom         GEOGRAPHY(Point, 4326) NOT NULL
);

-- Index for instant radial math
CREATE INDEX idx_locations_geom ON locations USING GIST (geom);

CREATE TABLE projects
(
    id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL, -- References organizations(id)
    title           VARCHAR(255) NOT NULL,
    description     TEXT         NOT NULL,
    status          VARCHAR(50)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING', 'ACTIVE', 'FLAGGED', 'REJECTED')),
    created_at      TIMESTAMPTZ           DEFAULT NOW()
);

-- The join table mapping a project to its official operating locations
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

    -- Composite foreign key guarantees consistency
    FOREIGN KEY (project_id, location_id) REFERENCES project_locations (project_id, location_id) ON DELETE CASCADE
);

-- Index for rapid chronological filtration
CREATE INDEX idx_shifts_start_time ON shifts (start_time);