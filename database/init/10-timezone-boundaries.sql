-- Timezone boundaries and location timezone
CREATE TABLE IF NOT EXISTS timezone_boundaries
(
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tzid VARCHAR(255)                 NOT NULL UNIQUE,
    geom GEOMETRY(MultiPolygon, 4326) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_timezone_boundaries_geom ON timezone_boundaries USING GIST (geom);

ALTER TABLE locations ADD COLUMN IF NOT EXISTS timezone VARCHAR(100);

INSERT INTO timezone_boundaries (tzid, geom) VALUES
('America/Denver', ST_GeomFromText('MULTIPOLYGON(((-109.05 36.99, -102.04 36.99, -102.04 41.01, -109.05 41.01, -109.05 36.99)))', 4326)),
('America/Chicago', ST_GeomFromText('MULTIPOLYGON(((-102.04 25.00, -85.00 25.00, -85.00 49.00, -102.04 49.00, -102.04 25.00)))', 4326)),
('America/New_York', ST_GeomFromText('MULTIPOLYGON(((-85.00 24.00, -65.00 24.00, -65.00 45.00, -85.00 45.00, -85.00 24.00)))', 4326)),
('America/Los_Angeles', ST_GeomFromText('MULTIPOLYGON(((-125.00 32.00, -114.00 32.00, -114.00 49.00, -125.00 49.00, -125.00 32.00)))', 4326))
ON CONFLICT (tzid) DO NOTHING;

UPDATE locations l
SET timezone = (
    SELECT tb.tzid
    FROM timezone_boundaries tb
    WHERE ST_Intersects(tb.geom, l.geom::geometry)
    ORDER BY ST_Area(tb.geom) ASC
    LIMIT 1
)
WHERE l.timezone IS NULL;
