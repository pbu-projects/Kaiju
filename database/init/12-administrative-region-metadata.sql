-- Administrative region metadata hierarchy and timezone
ALTER TABLE administrative_regions
    ADD COLUMN IF NOT EXISTS level INT DEFAULT 1,
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(10) DEFAULT 'US',
    ADD COLUMN IF NOT EXISTS state_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS time_zone VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_admin_regions_level ON administrative_regions (level);
CREATE INDEX IF NOT EXISTS idx_admin_regions_parent_id ON administrative_regions (parent_region_id);
CREATE INDEX IF NOT EXISTS idx_admin_regions_level_state ON administrative_regions (level, state_code);

-- Backfill existing seed data
-- Level 1: State level
UPDATE administrative_regions
SET level = 1, country_code = 'US', state_code = 'CO', time_zone = 'America/Denver'
WHERE parent_region_id IS NULL;

-- Level 2: County level
UPDATE administrative_regions
SET level = 2, country_code = 'US', state_code = 'CO', time_zone = 'America/Denver'
WHERE parent_region_id IN (SELECT id FROM administrative_regions WHERE level = 1);

-- Level 3: Municipalities and city cores
UPDATE administrative_regions
SET level = 3, country_code = 'US', state_code = 'CO', time_zone = 'America/Denver'
WHERE parent_region_id IN (
    SELECT id FROM administrative_regions WHERE level = 2
);

-- Intersect remaining regions with timezone boundaries if missing
UPDATE administrative_regions ar
SET time_zone = (
    SELECT tb.tzid
    FROM timezone_boundaries tb
    WHERE ST_Intersects(tb.geom, ar.geom::geometry)
    ORDER BY ST_Area(tb.geom) ASC
    LIMIT 1
)
WHERE ar.time_zone IS NULL;
