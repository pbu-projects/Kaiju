-- Add Census GEOID and cached area for administrative regions, and allow system-level audit logs
ALTER TABLE administrative_regions
    ADD COLUMN IF NOT EXISTS geoid VARCHAR(20),
    ADD COLUMN IF NOT EXISTS area_sq_m DOUBLE PRECISION;

CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_regions_geoid
    ON administrative_regions (geoid)
    WHERE geoid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_admin_regions_state_level_geoid
    ON administrative_regions (state_code, level, geoid);

UPDATE administrative_regions
SET area_sq_m = ST_Area(geom)
WHERE area_sq_m IS NULL;

-- Backfill GEOID for unambiguous Colorado seed rows (custom downtown cores stay NULL)
UPDATE administrative_regions
SET geoid = CASE id
    WHEN 'a0000000-0000-0000-0000-000000000001' THEN '08'
    WHEN 'a0000000-0000-0000-0000-000000000002' THEN '08031'
    WHEN 'a0000000-0000-0000-0000-000000000003' THEN '08041'
    WHEN 'a0000000-0000-0000-0000-000000000010' THEN '08005'
    WHEN 'a0000000-0000-0000-0000-000000000006' THEN '0820000'
    WHEN 'a0000000-0000-0000-0000-000000000007' THEN '0816000'
    WHEN 'a0000000-0000-0000-0000-000000000011' THEN '0804000'
    ELSE geoid
END
WHERE geoid IS NULL
  AND id IN (
      'a0000000-0000-0000-0000-000000000001',
      'a0000000-0000-0000-0000-000000000002',
      'a0000000-0000-0000-0000-000000000003',
      'a0000000-0000-0000-0000-000000000010',
      'a0000000-0000-0000-0000-000000000006',
      'a0000000-0000-0000-0000-000000000007',
      'a0000000-0000-0000-0000-000000000011'
  );

-- Allow system-level boundary syncs to record audit logs without a user actor
ALTER TABLE administrative_region_audit_logs
    ALTER COLUMN actor_id DROP NOT NULL;
