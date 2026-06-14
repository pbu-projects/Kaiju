import os
import random
import uuid
import re
from faker import Faker

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
    ]
}

def clean_url_part(text):
    # Remove special characters, handle spaces, and strip trailing dots
    clean = re.sub(r'[^a-zA-Z0-9]', '', text.split('.')[0])
    return clean.lower()

def generate_organizations(count=50):
    orgs = []
    for _ in range(count):
        org_id = str(uuid.uuid4())
        name = random.choice(THEMES['orgs']) + " " + fake.company_suffix()
        if any(o['name'] == name for o in orgs):
            name = f"{fake.city()} {name}"
        
        # Fixed "double period" bug by cleaning the name part before appending .org
        url_name = clean_url_part(name)
        website_url = f"https://www.{url_name}.org"
        
        # parent_id is NULL by default in this generator
        orgs.append({'id': org_id, 'name': name, 'website_url': website_url, 'parent_id': 'NULL'})
    return orgs

def generate_users(count=200):
    users = []
    roles = ['SUPER_ADMIN', 'MODERATOR', 'ORGANIZATION_LEADER', 'VOLUNTEER']
    for role in roles:
        user_id = str(uuid.uuid4())
        # Using fake.email() as requested
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
    # Updated project_type options per 01-schema.sql
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

def write_sql(filename, table, data):
    with open(filename, 'w', encoding='utf-8') as f:
        f.write(f"-- Supplemental data for {table} (Refined Global Data)\n")
        f.write(f"INSERT INTO {table} ({', '.join(data[0].keys())}) VALUES\n")
        
        rows = []
        for item in data:
            row_values = []
            for val in item.values():
                if val == 'NULL' or (isinstance(val, str) and val.startswith("'") and val.endswith("'")):
                    row_values.append(str(val))
                else:
                    clean_val = str(val).replace("'", "''")
                    row_values.append(f"'{clean_val}'")
            rows.append(f"({', '.join(row_values)})")
        
        f.write(",\n".join(rows))
        f.write(";\n")

if __name__ == "__main__":
    org_list = generate_organizations(50)
    user_list = generate_users(200)
    project_list = generate_projects(org_list, user_list, 100)
    
    write_sql('02-users.sql', 'users', user_list)
    write_sql('03-organizations.sql', 'organizations', org_list)
    write_sql('05-projects.sql', 'projects', project_list)
    
    print("Successfully generated refined themed global test data.")
