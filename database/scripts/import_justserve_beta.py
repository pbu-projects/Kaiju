#!/usr/bin/env python3
"""Gently paginate active projects from beta.justserve.org/graphql and upsert
US organizations, locations, projects, project_locations, and shifts into volunteer_db.
"""

import html
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

GRAPHQL_URL = "https://beta.justserve.org/graphql"
FALLBACK_ORG_ID = "99999999-0000-0000-0000-000000000001"
PAGE_SIZE = 100
SLEEP_BETWEEN_PAGES_SEC = 0.4

DB_CONTAINER = os.environ.get("DB_CONTAINER", "volunteer_db")
DB_USERNAME = os.environ.get("DB_USERNAME", "jimmy")
DB_NAME = os.environ.get("DB_NAME", "volunteer_monster")

QUERY = """
query($page: Int!, $pageSize: Int!) {
  projectsCount(input: { activeOnly: true, excludeUnlisted: true })
  projects(input: { activeOnly: true, excludeUnlisted: true }, pageNumber: $page, pageSize: $pageSize, sort: ID) {
    id
    title
    shortDescription
    longDescription
    statusId
    locationType
    volunteerRemotely
    volunteerFromAnywhere
    countryCode
    organizations {
      id
      name
      website
      url
      isVerified
      statusId
      unlisted
    }
    projectEventLocation {
      id
      locationName
      displayAddress
      displayCity
      displayCounty
      displayState
      displayPostalCode
      displayCountryCode
      latitude
      longitude
      timezone
    }
    projectEvents {
      id
      start
      end
      startDateTimeOffset
      endDateTimeOffset
      totalVolunteersNeeded
      shiftTitle
      timezone
      statusId
      projectEventLocation {
        id
        locationName
        displayAddress
        displayCity
        displayCounty
        displayState
        displayPostalCode
        displayCountryCode
        latitude
        longitude
        timezone
      }
    }
  }
}
"""


def fetch_page(page: int, retries: int = 4) -> dict:
    payload = json.dumps({"query": QUERY, "variables": {"page": page, "pageSize": PAGE_SIZE}}).encode("utf-8")
    for attempt in range(retries):
        req = urllib.request.Request(
            GRAPHQL_URL,
            data=payload,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "Kaiju-POC-Client/1.0",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as exc:
            if attempt == retries - 1:
                raise
            wait = 2 ** (attempt + 1)
            print(f"  Page {page} retry {attempt + 1} after {exc}; sleeping {wait}s", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError("Unreachable")


def clean_html(raw: str) -> str:
    text = re.sub(r"(?i)</(?:p|div|li|h[1-6])>|<br\s*/?>", "\n", raw)
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text).replace("\xa0", " ")
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in text.splitlines()]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def sql_str(val: str | None, max_len: int | None = None) -> str:
    if val is None:
        return "NULL"
    s = val.replace("\x00", "").strip()
    if max_len is not None:
        s = s[:max_len]
    return "'" + s.replace("'", "''") + "'"


def is_us_project(proj: dict) -> bool:
    cc = (proj.get("countryCode") or "").strip().lower()
    if cc in {"us", "usa", "united states"}:
        return True
    loc = proj.get("projectEventLocation") or {}
    lcc = (loc.get("displayCountryCode") or "").strip().lower()
    return lcc in {"us", "usa"}


def map_status(status_id: int | None) -> str:
    if status_id == 1:
        return "ACTIVE"
    if status_id == 2:
        return "PENDING"
    if status_id == 7:
        return "REJECTED"
    return "DRAFT"


def map_project_type(loc_type: str | None) -> str:
    if loc_type == "REGIONAL":
        return "REGIONAL"
    if loc_type == "REMOTE":
        return "OPEN_DOOR"
    return "STANDARD"


def run_psql(sql: str) -> None:
    proc = subprocess.run(
        ["docker", "exec", "-i", DB_CONTAINER, "psql", "-U", DB_USERNAME, "-d", DB_NAME, "-v", "ON_ERROR_STOP=1", "-q"],
        input=sql.encode("utf-8"),
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"psql failed: {proc.stderr.decode('utf-8')[:600]}")


def build_batch_sql(projects: list[dict]) -> tuple[str, int, int, int, int]:
    stmts = ["BEGIN;"]
    org_count = 0
    loc_count = 0
    proj_count = 0
    shift_count = 0

    for p in projects:
        if not is_us_project(p):
            continue
        pid = p.get("id")
        title = (p.get("title") or "").strip()
        if not pid or not title:
            continue

        orgs = p.get("organizations") or []
        org_id = FALLBACK_ORG_ID
        if orgs:
            o = orgs[0]
            oid = o.get("id")
            oname = (o.get("name") or "").strip()
            if oid and oname:
                org_id = oid
                website = o.get("website") or (f"https://www.justserve.org/{o['url']}" if o.get("url") else None)
                ver_status = "VERIFIED" if o.get("isVerified") else "UNVERIFIED"
                is_pub = "FALSE" if o.get("unlisted") else "TRUE"
                stmts.append(
                    f"INSERT INTO organizations (id, name, website_url, is_public, verification_status) "
                    f"VALUES ({sql_str(oid)}::uuid, {sql_str(oname, 255)}, {sql_str(website, 255)}, {is_pub}, {sql_str(ver_status)}) "
                    f"ON CONFLICT (id) DO NOTHING;"
                )
                org_count += 1

        loc_candidates = []
        if p.get("projectEventLocation"):
            loc_candidates.append(p["projectEventLocation"])
        for ev in p.get("projectEvents") or []:
            if ev.get("projectEventLocation"):
                loc_candidates.append(ev["projectEventLocation"])

        primary_loc_id = None
        seen_locs = set()
        for loc in loc_candidates:
            lid = loc.get("id")
            lat = loc.get("latitude")
            lon = loc.get("longitude")
            if not lid or lat is None or lon is None or lid in seen_locs:
                continue
            if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
                continue
            seen_locs.add(lid)
            if primary_loc_id is None:
                primary_loc_id = lid
            lname = (loc.get("locationName") or loc.get("displayAddress") or loc.get("displayCity") or title)[:255]
            addr = (loc.get("displayAddress") or loc.get("displayCity") or "United States")[:255]
            city = (loc.get("displayCity") or "Unknown")[:100]
            state = loc.get("displayState")
            postal = loc.get("displayPostalCode")
            stmts.append(
                f"INSERT INTO locations (id, name, address_line, city, state_province, postal_code, country_code, geom, timezone) "
                f"VALUES ({sql_str(lid)}::uuid, {sql_str(lname, 255)}, {sql_str(addr, 255)}, {sql_str(city, 100)}, "
                f"{sql_str(state, 100)}, {sql_str(postal, 20)}, 'US', ST_SetSRID(ST_MakePoint({lon}, {lat}), 4326)::geography, "
                f"COALESCE((SELECT tzid FROM timezone_boundaries WHERE ST_Intersects(geom, ST_SetSRID(ST_MakePoint({lon}, {lat}), 4326)) ORDER BY ST_Area(geom::geography) ASC LIMIT 1), 'America/Denver')) "
                f"ON CONFLICT (id) DO NOTHING;"
            )
            loc_count += 1

        desc = clean_html(p.get("longDescription") or p.get("shortDescription") or "") or title
        ptype = map_project_type(p.get("locationType"))
        pstatus = map_status(p.get("statusId"))

        if primary_loc_id:
            region_expr = (
                f"(SELECT r.id FROM administrative_regions r, locations l "
                f"WHERE l.id = {sql_str(primary_loc_id)}::uuid AND r.level = 2 AND ST_Covers(r.geom, l.geom) "
                f"ORDER BY COALESCE(r.area_sq_m, ST_Area(r.geom)) ASC LIMIT 1)"
            )
        else:
            region_expr = "NULL"

        stmts.append(
            f"INSERT INTO projects (id, organization_id, managing_region_id, title, description, project_type, status) "
            f"VALUES ({sql_str(pid)}::uuid, {sql_str(org_id)}::uuid, {region_expr}, {sql_str(title, 255)}, {sql_str(desc)}, {sql_str(ptype)}, {sql_str(pstatus)}) "
            f"ON CONFLICT (id) DO UPDATE SET description = EXCLUDED.description;"
        )
        proj_count += 1

        for lid in seen_locs:
            stmts.append(
                f"INSERT INTO project_locations (project_id, location_id) "
                f"VALUES ({sql_str(pid)}::uuid, {sql_str(lid)}::uuid) ON CONFLICT DO NOTHING;"
            )

        is_virtual = bool(p.get("volunteerRemotely") or p.get("locationType") == "REMOTE")
        for ev in p.get("projectEvents") or []:
            eid = ev.get("id")
            start_ts = ev.get("startDateTimeOffset") or ev.get("start")
            end_ts = ev.get("endDateTimeOffset") or ev.get("end")
            if not eid or not start_ts or not end_ts:
                continue
            ev_loc_id = (ev.get("projectEventLocation") or {}).get("id")
            if ev_loc_id not in seen_locs:
                ev_loc_id = primary_loc_id
            ev_virtual = is_virtual or (ev_loc_id is None)
            cap = ev.get("totalVolunteersNeeded")
            cap_expr = str(int(cap)) if isinstance(cap, int) and cap > 0 else "NULL"
            loc_expr = "NULL" if ev_virtual else f"{sql_str(ev_loc_id)}::uuid"
            stmts.append(
                f"INSERT INTO shifts (id, project_id, is_virtual, location_id, start_time, end_time, capacity) "
                f"VALUES ({sql_str(eid)}::uuid, {sql_str(pid)}::uuid, {'TRUE' if ev_virtual else 'FALSE'}, {loc_expr}, "
                f"{sql_str(start_ts)}::timestamptz, {sql_str(end_ts)}::timestamptz, {cap_expr}) "
                f"ON CONFLICT (id) DO NOTHING;"
            )
            shift_count += 1

    stmts.append("COMMIT;")
    return "\n".join(stmts), org_count, loc_count, proj_count, shift_count


def main() -> None:
    run_psql(
        f"INSERT INTO organizations (id, name, website_url, is_public, verification_status) "
        f"VALUES ('{FALLBACK_ORG_ID}'::uuid, 'JustServe Community', 'https://www.justserve.org', TRUE, 'VERIFIED') "
        f"ON CONFLICT (id) DO NOTHING;"
    )

    page = 1
    total_projects = 0
    total_orgs = 0
    total_locs = 0
    total_shifts = 0

    while True:
        data = fetch_page(page)
        if data.get("errors"):
            print(f"Page {page} GraphQL errors: {data['errors']}", file=sys.stderr)
        projs = (data.get("data") or {}).get("projects") or []
        total_avail = (data.get("data") or {}).get("projectsCount")
        if page == 1:
            print(f"Total active projects reported by beta.justserve.org: {total_avail}")
        if not projs:
            break

        sql, oc, lc, pc, sc = build_batch_sql(projs)
        if pc > 0:
            run_psql(sql)
        total_orgs += oc
        total_locs += lc
        total_projects += pc
        total_shifts += sc

        if page % 10 == 0 or len(projs) < PAGE_SIZE:
            print(f"  Page {page} (fetched {len(projs)}): imported US projects={total_projects}, locs={total_locs}, shifts={total_shifts}")
        if len(projs) < PAGE_SIZE:
            break
        page += 1
        time.sleep(SLEEP_BETWEEN_PAGES_SEC)

    print(f"Done! Imported US projects={total_projects}, orgs={total_orgs}, locs={total_locs}, shifts={total_shifts}")


if __name__ == "__main__":
    main()
