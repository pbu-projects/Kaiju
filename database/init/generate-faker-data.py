#!/usr/bin/env python3

import os
import random
import uuid
import re
from faker import Faker
from datetime import datetime, timedelta

# Initialize Faker with multiple locales
locales = ['en_US', 'ja_JP', 'fr_FR', 'es_ES', 'de_DE', 'zh_CN', 'it_IT', 'pt_BR']
fake = Faker(locales)

THEMES = {
    'orgs': [
        "Hyrule Restoration Society", "Starfleet Volunteer Corps", "Rivendell Preservation Trust",
        "Mushroom Kingdom Green Team", "Aurelia Humanitarian Foundation", "Stark Industries Community Outreach",
        "Wayne Foundation Youth Services", "Xavier School for Gifted Volunteers", "Naboo Cultural Exchange",
        "Mos Eisley Community Center", "Cloud City Sustainability Group", "Cybertron Rehabilitation Project",
        "Aperture Science Enrichment Center", "Black Mesa Wellness Initiative",
        "Zootopia Inclusion Council", "Hogwarts Alumni Charity", "The Shire Agricultural Guild",
        "Isengard Forest Recovery", "Wakanda Outreach Program", "The Resistance Relief Fund",
        "Johto Wildlife Protection", "Kanto Urban Development", "Galactic Senate Social Services"
    ],
    'project_prefixes': [
        "Rebuilding", "Protecting", "Digital", "International", "Community", "Green", "Urban", "Sustainable",
        "Virtual", "Collaborative", "Legacy", "Future", "Heritage", "Educational", "Creative"
    ],
    'project_suffixes': [
        "Initiative", "Movement", "Operation", "Task Force", "Fellowship", "Program", "Alliance", "Network",
        "Foundation", "Consortium", "Exchange", "Clinic", "Workshop", "Festival"
    ],
    'game_movie_topics': [
        "the Triforce", "the Force", "the Rings", "the Pokédex", "the Metaverse", "the Grid", "the Frontier",
        "the Galaxy", "the Multiverse", "the Arcade", "the Level Up", "the Power-Up", "the Quest"
    ],
    'roles': [
        "Lead Developer", "Environment Artist", "Quest Designer", "Resource Manager", "Community Moderator",
        "Safety Officer", "Logistics Coordinator", "Translation Specialist", "Health Inspector", "Support Staff"
    ]
}

def clean_url_part(text):
    clean = re.sub(r'[^a-zA-Z0-9]', '', text.split('.')[0])
    return clean.lower()

def generate_organizations(count=50):
    orgs = []
    for _ in range(count):
        org_id = str(uuid.uuid4())
        name = random.choice(THEMES['orgs']) + " " + fake.company_suffix()
        if any(o['name'] == name for o in orgs):
            name = f"{fake.city()} {name}"
        url_name = clean_url_part(name)
        website_url = f"https://www.{url_name}.org"
        orgs.append({'id': org_id, 'name': name, 'website_url': website_url, 'parent_id': 'NULL'})
    return orgs

def generate_users(count=200):
    users = []
    roles = ['GLOBAL_ADMIN', 'REGION_DIRECTOR', 'REGION_AGENT', 'STANDARD_USER']
    for role in roles:
        user_id = str(uuid.uuid4())
        email = fake.unique.email()
        created_at = fake.date_time_this_year().isoformat()
        users.append({'id': user_id, 'email': email, 'role': role, 'created_at': created_at})
    
    for _ in range(count - len(roles)):
        user_id = str(uuid.uuid4())
        email = fake.unique.email()
        role = random.choices(roles, weights=[5, 10, 20, 65])[0]
        created_at = fake.date_time_this_year().isoformat()
        users.append({'id': user_id, 'email': email, 'role': role, 'created_at': created_at})
    return users

def generate_projects(orgs, users, count=100):
    projects = []
    p_types = ['STANDARD', 'OPEN_DOOR', 'REGIONAL']
    statuses = ['DRAFT', 'PENDING', 'ACTIVE', 'FLAGGED', 'REJECTED']
    
    for _ in range(count):
        p_id = str(uuid.uuid4())
        org = random.choice(orgs)
        title = f"{random.choice(THEMES['project_prefixes'])} {random.choice(THEMES['game_movie_topics'])} {random.choice(THEMES['project_suffixes'])}"
        description = fake.paragraph(nb_sentences=3)
        p_type = random.choice(p_types)
        status = random.choices(statuses, weights=[10, 10, 60, 10, 10])[0]
        created_at = fake.date_time_this_year().isoformat()
        deleted_at = 'NULL'
        deleted_by = 'NULL'
        if random.random() < 0.05:
            deleted_at = f"'{fake.date_time_this_year().isoformat()}'"
            deleted_by = f"'{random.choice(users)['id']}'"
            
        projects.append({
            'id': p_id,
            'organization_id': org['id'],
            'title': title,
            'description': description,
            'project_type': p_type,
            'status': status,
            'created_at': created_at,
            'deleted_at': deleted_at,
            'deleted_by': deleted_by
        })
    return projects

def format_sql_value(val):
    if val is None or val == 'NULL':
        return 'NULL'
    if isinstance(val, bool):
        return 'true' if val else 'false'
    if isinstance(val, str):
        if (val.startswith("'") and val.endswith("'")) or val.startswith('ST_'):
            return val
        clean_val = val.replace("'", "''")
        return f"'{clean_val}'"
    clean_val = str(val).replace("'", "''")
    return f"'{clean_val}'"

def write_sql(filename, table, data):
    if not data:
        return
    columns = ", ".join(data[0].keys())
    rows = ",\n".join(
        f"({', '.join(format_sql_value(val) for val in item.values())})"
        for item in data
    )
    with open(filename, 'w', encoding='utf-8') as f:
        f.write(f"-- Supplemental data for {table}\n")
        f.write(f"INSERT INTO {table} ({columns}) VALUES\n")
        f.write(rows)
        f.write(";\n")

if __name__ == "__main__":
    org_list = generate_organizations(50)
    user_list = generate_users(200)
    project_list = generate_projects(org_list, user_list, 100)
    
    locations = []
    for _ in range(30):
        loc_id = str(uuid.uuid4())
        name = f"{fake.city()} {random.choice(['Center', 'Outpost', 'Hub', 'Facility'])}"
        locations.append({
            'id': loc_id,
            'name': name,
            'address_line': fake.street_address(),
            'city': fake.city(),
            'state_province': fake.state_abbr(),
            'postal_code': fake.postcode(),
            'country_code': fake.country_code(),
            'geom': f"ST_GeographyFromText('POINT({float(fake.longitude())} {float(fake.latitude())})')"
        })
        
    project_locations = []
    shifts = []
    
    # Scenarios:
    # 1. 30% projects: Multiple shifts, multiple locations
    # 2. 40% projects: Multiple shifts, one location
    # 3. 20% projects: Remote shifts (location_id = NULL)
    # 4. 10% projects: No shifts
    
    random.shuffle(project_list)
    
    for i, project in enumerate(project_list):
        p_id = project['id']
        
        if i < 30:
            selected_locs = random.sample(locations, k=random.randint(2, 4))
            for loc in selected_locs:
                project_locations.append({'project_id': p_id, 'location_id': loc['id']})
                for _ in range(random.randint(1, 2)):
                    start = datetime.now() + timedelta(days=random.randint(1, 30), hours=random.randint(0, 23))
                    shifts.append({
                        'id': str(uuid.uuid4()),
                        'project_id': p_id,
                        'is_virtual': False,
                        'location_id': f"'{loc['id']}'",
                        'start_time': start.isoformat(),
                        'end_time': (start + timedelta(hours=random.randint(2, 8))).isoformat()
                    })
        elif i < 70:
            loc = random.choice(locations)
            project_locations.append({'project_id': p_id, 'location_id': loc['id']})
            for _ in range(random.randint(2, 5)):
                start = datetime.now() + timedelta(days=random.randint(1, 30), hours=random.randint(0, 23))
                shifts.append({
                    'id': str(uuid.uuid4()),
                    'project_id': p_id,
                    'is_virtual': False,
                    'location_id': f"'{loc['id']}'",
                    'start_time': start.isoformat(),
                    'end_time': (start + timedelta(hours=random.randint(2, 8))).isoformat()
                })
        elif i < 90:
            for _ in range(random.randint(1, 3)):
                start = datetime.now() + timedelta(days=random.randint(1, 30), hours=random.randint(0, 23))
                shifts.append({
                    'id': str(uuid.uuid4()),
                    'project_id': p_id,
                    'is_virtual': True,
                    'location_id': 'NULL',
                    'start_time': start.isoformat(),
                    'end_time': (start + timedelta(hours=random.randint(2, 8))).isoformat()
                })
        else: # No shifts
            pass

    write_sql('02-users.sql', 'users', user_list)
    write_sql('03-organizations.sql', 'organizations', org_list)
    write_sql('04-locations.sql', 'locations', locations)
    write_sql('05-projects.sql', 'projects', project_list)
    write_sql('06-project-locations.sql', 'project_locations', project_locations)
    write_sql('07-shifts.sql', 'shifts', shifts)
    
    print("Successfully generated themed global test data with complex shift scenarios.")
