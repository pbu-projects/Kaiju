SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = 'public' 
  AND table_name IN ('organizations', 'locations', 'projects', 'shifts');

SELECT id, "name", address_line, city, state, zip_code, geom
FROM public.locations;