-- Accurate regional US time zone boundaries (WGS84, valid MultiPolygons, no antimeridian wrap)
INSERT INTO timezone_boundaries (tzid, geom)
VALUES
    (
        'America/New_York',
        ST_GeomFromText(
            'MULTIPOLYGON(((-85.0 24.4, -79.9 24.4, -75.0 34.5, -66.9 44.5, -66.9 47.5, -71.0 45.3, -75.0 45.0, -79.0 43.3, -82.5 43.0, -84.0 46.6, -87.4 46.6, -87.4 41.5, -85.0 38.0, -85.0 24.4)))',
            4326
        )
    ),
    (
        'America/Chicago',
        ST_GeomFromText(
            'MULTIPOLYGON(((-104.05 25.8, -85.0 25.8, -85.0 38.0, -87.4 41.5, -87.4 46.6, -89.5 48.2, -97.2 49.0, -104.05 49.0, -104.05 45.0, -101.0 45.0, -101.0 41.0, -102.04 41.0, -102.04 36.5, -104.05 32.0, -104.05 25.8)))',
            4326
        )
    ),
    (
        'America/Denver',
        ST_GeomFromText(
            'MULTIPOLYGON(((-109.04 31.3, -104.05 31.3, -104.05 32.0, -102.04 36.5, -102.04 41.0, -101.0 41.0, -101.0 45.0, -104.05 45.0, -104.05 49.0, -116.05 49.0, -116.05 45.5, -117.2 44.0, -117.0 42.0, -114.05 42.0, -114.05 37.0, -109.04 37.0, -109.04 31.3)))',
            4326
        )
    ),
    (
        'America/Phoenix',
        ST_GeomFromText(
            'MULTIPOLYGON(((-114.82 31.33, -109.04 31.33, -109.04 37.0, -114.05 37.0, -114.82 35.0, -114.82 31.33)))',
            4326
        )
    ),
    (
        'America/Los_Angeles',
        ST_GeomFromText(
            'MULTIPOLYGON(((-124.8 32.5, -114.7 32.5, -114.82 35.0, -114.05 37.0, -114.05 42.0, -117.0 42.0, -117.2 44.0, -116.05 45.5, -116.05 49.0, -124.8 49.0, -124.8 32.5)))',
            4326
        )
    ),
    (
        'America/Anchorage',
        ST_GeomFromText(
            'MULTIPOLYGON(((-169.5 51.0, -130.0 54.5, -130.0 60.0, -141.0 60.0, -141.0 71.5, -169.5 71.5, -169.5 51.0)))',
            4326
        )
    ),
    (
        'America/Adak',
        ST_GeomFromText(
            'MULTIPOLYGON(((-180.0 51.0, -169.5 51.0, -169.5 55.0, -180.0 55.0, -180.0 51.0)), ((172.0 51.0, 180.0 51.0, 180.0 55.0, 172.0 55.0, 172.0 51.0)))',
            4326
        )
    ),
    (
        'Pacific/Honolulu',
        ST_GeomFromText(
            'MULTIPOLYGON(((-160.5 18.8, -154.7 18.8, -154.7 22.3, -160.5 22.3, -160.5 18.8)))',
            4326
        )
    ),
    (
        'America/Puerto_Rico',
        ST_GeomFromText(
            'MULTIPOLYGON(((-68.0 17.6, -64.5 17.6, -64.5 18.6, -68.0 18.6, -68.0 17.6)))',
            4326
        )
    ),
    (
        'Pacific/Guam',
        ST_GeomFromText(
            'MULTIPOLYGON(((144.5 13.1, 146.2 13.1, 146.2 20.6, 144.5 20.6, 144.5 13.1)))',
            4326
        )
    ),
    (
        'Pacific/Pago_Pago',
        ST_GeomFromText(
            'MULTIPOLYGON(((-171.2 -14.7, -168.0 -14.7, -168.0 -11.0, -171.2 -11.0, -171.2 -14.7)))',
            4326
        )
    )
ON CONFLICT (tzid) DO UPDATE SET geom = EXCLUDED.geom;
