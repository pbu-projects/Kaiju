-- Migration 09: Seed deterministic test personas and 3-tier hierarchy
-- Stage 1 of Issue #59
--
-- Baseline seed personas for local development and test environments,
-- mirroring the supplemental data pattern of 02-users.sql through 08-global-locations.sql.
-- Provides deterministic regional and organizational hierarchies alongside paired
-- test personas for authorization and integration testing.

-- 1. Administrative Regions (3-Tier Regional Hierarchy)
-- Level 1: Colorado State Region
-- Level 2: Denver County Region, El Paso County Region
-- Level 3: Denver Downtown Core, Colorado Springs Downtown Core

INSERT INTO administrative_regions (id, name, parent_region_id, geom) VALUES
(
    'a0000000-0000-0000-0000-000000000001',
    'Colorado State Region',
    NULL,
    ST_GeographyFromText('POLYGON((-109.05 37.0, -102.05 37.0, -102.05 41.0, -109.05 41.0, -109.05 37.0))')
),
(
    'a0000000-0000-0000-0000-000000000002',
    'Denver County Region',
    'a0000000-0000-0000-0000-000000000001',
    ST_GeographyFromText('POLYGON((-105.11 39.61, -104.60 39.61, -104.60 39.91, -105.11 39.91, -105.11 39.61))')
),
(
    'a0000000-0000-0000-0000-000000000003',
    'El Paso County Region',
    'a0000000-0000-0000-0000-000000000001',
    ST_GeographyFromText('POLYGON((-105.00 38.50, -104.05 38.50, -104.05 39.15, -105.00 39.15, -105.00 38.50))')
),
(
    'a0000000-0000-0000-0000-000000000004',
    'Denver Downtown Core',
    'a0000000-0000-0000-0000-000000000002',
    ST_GeographyFromText('POLYGON((-105.01 39.73, -104.97 39.73, -104.97 39.76, -105.01 39.76, -105.01 39.73))')
),
(
    'a0000000-0000-0000-0000-000000000005',
    'Colorado Springs Downtown',
    'a0000000-0000-0000-0000-000000000003',
    ST_GeographyFromText('POLYGON((-104.84 38.82, -104.80 38.82, -104.80 38.85, -104.84 38.85, -104.84 38.82))')
);

-- 2. Organizations (3-Tier Organizational Hierarchy)
-- Level 1: National Relief Network (Supra-State, parent_id = NULL, is_public = true, status = VERIFIED)
-- Level 2: Colorado State Chapter (parent_id = National, VERIFIED), Utah State Chapter (parent_id = National, VERIFIED)
-- Level 3: Denver Metro Branch (parent_id = Colorado, VERIFIED), Colorado Springs Branch (parent_id = Colorado, VERIFIED)
-- Standalone: Local Grassroots Co-op (parent_id = NULL, UNVERIFIED)

INSERT INTO organizations (id, name, website_url, parent_id, is_public, verification_status) VALUES
(
    'b0000000-0000-0000-0000-000000000001',
    'National Relief Network',
    'https://nationalrelief.example.org',
    NULL,
    TRUE,
    'VERIFIED'
),
(
    'b0000000-0000-0000-0000-000000000002',
    'Colorado State Chapter',
    'https://colorado.nationalrelief.example.org',
    'b0000000-0000-0000-0000-000000000001',
    TRUE,
    'VERIFIED'
),
(
    'b0000000-0000-0000-0000-000000000003',
    'Utah State Chapter',
    'https://utah.nationalrelief.example.org',
    'b0000000-0000-0000-0000-000000000001',
    TRUE,
    'VERIFIED'
),
(
    'b0000000-0000-0000-0000-000000000004',
    'Denver Metro Branch',
    'https://denver.nationalrelief.example.org',
    'b0000000-0000-0000-0000-000000000002',
    TRUE,
    'VERIFIED'
),
(
    'b0000000-0000-0000-0000-000000000005',
    'Colorado Springs Branch',
    'https://cos.nationalrelief.example.org',
    'b0000000-0000-0000-0000-000000000002',
    TRUE,
    'VERIFIED'
),
(
    'b0000000-0000-0000-0000-000000000006',
    'Local Grassroots Co-op',
    'https://localgrassroots.example.org',
    NULL,
    TRUE,
    'UNVERIFIED'
);

-- 3. Link organization_regions
INSERT INTO organization_regions (organization_id, region_id) VALUES
('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001'),
('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001'),
('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000002'),
('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000004'),
('b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000003'),
('b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000005'),
('b0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000004');

-- 4. Users (System, Region, Org, Independent)
-- Note: jenny.new@kaiju.local is intentionally omitted from the database to test Just-In-Time (JIT) provisioning.
INSERT INTO users (id, email, role, created_at) VALUES
-- Global System Admins
('c0000000-0000-0000-0000-000000000001', 'sarah.admin1@kaiju.local', 'GLOBAL_ADMIN', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000002', 'gary.admin2@kaiju.local', 'GLOBAL_ADMIN', '2026-06-01T00:00:00+00:00'),

-- State Region (Level 1)
('c0000000-0000-0000-0000-000000000003', 'rachel.state.director1@kaiju.local', 'REGION_DIRECTOR', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000004', 'robert.state.director2@kaiju.local', 'REGION_DIRECTOR', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000005', 'samuel.state.agent@kaiju.local', 'REGION_AGENT', '2026-06-01T00:00:00+00:00'),

-- County Region (Level 2)
('c0000000-0000-0000-0000-000000000006', 'daniel.county.director1@kaiju.local', 'REGION_DIRECTOR', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000007', 'diana.county.director2@kaiju.local', 'REGION_DIRECTOR', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000008', 'connie.county.agent@kaiju.local', 'REGION_AGENT', '2026-06-01T00:00:00+00:00'),

-- Sibling County (Level 2)
('c0000000-0000-0000-0000-000000000009', 'ethan.sibling.director1@kaiju.local', 'REGION_DIRECTOR', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000010', 'emma.sibling.director2@kaiju.local', 'REGION_DIRECTOR', '2026-06-01T00:00:00+00:00'),

-- City Region (Level 3)
('c0000000-0000-0000-0000-000000000011', 'clara.city.lead1@kaiju.local', 'REGION_AGENT', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000012', 'cameron.city.lead2@kaiju.local', 'REGION_AGENT', '2026-06-01T00:00:00+00:00'),

-- National Org (Level 1)
('c0000000-0000-0000-0000-000000000013', 'nathan.national.admin1@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000014', 'nicole.national.admin2@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000015', 'noah.national.manager@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),

-- State Chapter Org (Level 2)
('c0000000-0000-0000-0000-000000000016', 'steven.state.admin1@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000017', 'sophia.state.admin2@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000018', 'susan.state.manager@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),

-- Sibling State Chapter Org (Level 2)
('c0000000-0000-0000-0000-000000000019', 'ursula.utah.admin1@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000020', 'ulysses.utah.admin2@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),

-- Local Branch Org (Level 3)
('c0000000-0000-0000-0000-000000000021', 'leo.local.admin1@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000022', 'lisa.local.admin2@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000023', 'laura.local.manager@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000024', 'luke.local.member@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),

-- Independent Volunteers
('c0000000-0000-0000-0000-000000000025', 'victor.volunteer1@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00'),
('c0000000-0000-0000-0000-000000000026', 'valerie.volunteer2@kaiju.local', 'STANDARD_USER', '2026-06-01T00:00:00+00:00');

-- 5. Region Users (region_users)
INSERT INTO region_users (user_id, region_id, role) VALUES
-- State Region (Level 1)
('c0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'REGION_DIRECTOR'),
('c0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'REGION_DIRECTOR'),
('c0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'REGION_AGENT'),

-- Denver County (Level 2)
('c0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000002', 'REGION_DIRECTOR'),
('c0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000002', 'REGION_DIRECTOR'),
('c0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000002', 'REGION_AGENT'),

-- El Paso County (Sibling Level 2)
('c0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000003', 'REGION_DIRECTOR'),
('c0000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000003', 'REGION_DIRECTOR'),

-- Denver Downtown Core (Level 3)
('c0000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000004', 'REGION_AGENT'),
('c0000000-0000-0000-0000-000000000012', 'a0000000-0000-0000-0000-000000000004', 'REGION_AGENT');

-- 6. Organization Users (organization_users)
INSERT INTO organization_users (user_id, organization_id, role) VALUES
-- National Relief Network (Level 1)
('c0000000-0000-0000-0000-000000000013', 'b0000000-0000-0000-0000-000000000001', 'ORG_ADMIN'),
('c0000000-0000-0000-0000-000000000014', 'b0000000-0000-0000-0000-000000000001', 'ORG_ADMIN'),
('c0000000-0000-0000-0000-000000000015', 'b0000000-0000-0000-0000-000000000001', 'ORG_MANAGER'),

-- Colorado State Chapter (Level 2)
('c0000000-0000-0000-0000-000000000016', 'b0000000-0000-0000-0000-000000000002', 'ORG_ADMIN'),
('c0000000-0000-0000-0000-000000000017', 'b0000000-0000-0000-0000-000000000002', 'ORG_ADMIN'),
('c0000000-0000-0000-0000-000000000018', 'b0000000-0000-0000-0000-000000000002', 'ORG_MANAGER'),

-- Utah State Chapter (Level 2)
('c0000000-0000-0000-0000-000000000019', 'b0000000-0000-0000-0000-000000000003', 'ORG_ADMIN'),
('c0000000-0000-0000-0000-000000000020', 'b0000000-0000-0000-0000-000000000003', 'ORG_ADMIN'),

-- Denver Metro Branch (Level 3)
('c0000000-0000-0000-0000-000000000021', 'b0000000-0000-0000-0000-000000000004', 'ORG_ADMIN'),
('c0000000-0000-0000-0000-000000000022', 'b0000000-0000-0000-0000-000000000004', 'ORG_ADMIN'),
('c0000000-0000-0000-0000-000000000023', 'b0000000-0000-0000-0000-000000000004', 'ORG_MANAGER'),
('c0000000-0000-0000-0000-000000000024', 'b0000000-0000-0000-0000-000000000004', 'ORG_MEMBER');
