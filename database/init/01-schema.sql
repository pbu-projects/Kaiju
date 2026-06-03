-- Enable PostGIS spatial engine
CREATE EXTENSION IF NOT EXISTS postgis;

-- 1. Base independent table for partners
CREATE TABLE organizations
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    website_url VARCHAR(255),
    created_at  TIMESTAMPTZ      DEFAULT NOW()
);

-- 2. Base independent table for geospatial tracking
CREATE TABLE locations
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255),
    address_line TEXT                   NOT NULL,
    city         VARCHAR(100)           NOT NULL,
    state        VARCHAR(50)            NOT NULL,
    zip_code     VARCHAR(20),
    -- PostGIS geography type defaults strictly to meters
    geom         GEOGRAPHY(Point, 4326) NOT NULL
);

-- Spatial index to optimize bounding-box and radius queries
CREATE INDEX idx_locations_geom ON locations USING GIST (geom);

-- 3. Core domain entity mapping orgs to locations
CREATE TABLE projects
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    location_id     UUID         REFERENCES locations (id) ON DELETE SET NULL,
    title           VARCHAR(255) NOT NULL,
    description     TEXT         NOT NULL,
    project_type    VARCHAR(50)  NOT NULL, -- 'ONGOING' or 'EVENT'
    created_at      TIMESTAMPTZ      DEFAULT NOW()
);

-- 4. Time-slot allocation layer
CREATE TABLE shifts
(
    id           UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    project_id   UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    start_time   TIMESTAMPTZ NOT NULL,
    end_time     TIMESTAMPTZ NOT NULL,
    capacity     INT         NOT NULL DEFAULT 1,
    spots_filled INT         NOT NULL DEFAULT 0,
    -- Prevent exact time-slot duplicates per project
    UNIQUE (project_id, start_time, end_time)
);