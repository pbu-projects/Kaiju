-- Insert an Organization
INSERT INTO organizations (name, website_url) VALUES 
('Utah Food Bank', 'https://www.utahfoodbank.org');

-- Insert Locations (Using Bountiful, Centerville, and Ogden for distance testing)
INSERT INTO locations (name, address_line, city, state_province, country_code, geom) VALUES
('Main Pantry', '123 Main St', 'Bountiful', 'UT', 'US', ST_GeographyFromText('POINT(-111.8804 40.8906)')),
('North Sorting Facility', '456 Center St', 'Centerville', 'UT', 'US', ST_GeographyFromText('POINT(-111.8722 40.9180)')),
('Ogden Warehouse', '789 Far Ave', 'Ogden', 'UT', 'US', ST_GeographyFromText('POINT(-111.9738 41.2230)'));

-- Insert a Project linked to the Bountiful Location
INSERT INTO projects (organization_id, location_id, title, description, project_type) 
SELECT 
    o.id, 
    l.id, 
    'Weekend Food Sorting', 
    'Help sort canned goods.', 
    'EVENT'
FROM organizations o, locations l 
WHERE o.name = 'Utah Food Bank' AND l.name = 'Main Pantry';
