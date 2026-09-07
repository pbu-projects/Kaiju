# Comprehensive Human Strategy & Empathy Maps: Kaiju (Volunteer Monster)

**Date**: September 2026  
**Target Platform**: Kaiju (Backend Service for Volunteer Monster)  
**Stack**: Micronaut 5, Java 25, PostgreSQL 17 + PostGIS, Flyway, JTE, Compose Multiplatform, Authentik  
**Companion Diagram**: [`docs/human-strategy-personas.drawio`](./human-strategy-personas.drawio)  
**Methodological Standard**: Nielsen Norman Group (NN/g) Proto-Personas & 4-Quadrant Empathy Mapping  

---

## Executive Summary: The Dual Human Spectrums & The Domain Chasm

As established in early architectural planning, the Kaiju platform has achieved an elite, high-performance foundation in **Phase 1: Spatial Discovery & Core Data**. Through PostgreSQL 17, PostGIS `GEOGRAPHY(Point, 4326)` and `Polygon` indexing, Fowler's Single Table Inheritance (STI), and Micronaut 5 compile-time dependency injection, the system excels at answering:
> *"Where is an initiative located, what type of opportunity is it, and within which civic boundary does it reside?"*

However, an enterprise volunteer management ecosystem encompasses **two distinct human spectrums**:

1. **The Volunteer Spectrum (Motivation & Barrier vs. Commitment)**:
   * **Spontaneous / Proximity-First Volunteers (Maya)**: Seeking low-friction, 2-to-3-hour opportunities near home. High abandonment risk if faced with login walls, paperwork, or mobile lag.
   * **Purpose-Driven & Skill-Building Volunteers (David)**: Seeking cause alignment (#wildlife, #climate) and professional skill utilization (#gis, #data-analysis). Willing to complete background checks, attend orientations, and commit to multi-month recurring schedules.
   * **Academic & Portfolio-Building Students (Chloe)**: Seeking 100+ verified service hours for college admissions, National Honor Society, or club philanthropy. Requires group slot reservations and official, accredited PDF transcripts.
   * **Compliance & Mandated Volunteers (Raymond)**: Fulfilling court-ordered community restitution or SNAP ABAWD monthly work requirements. Requires explicit court/SNAP eligibility filters, GPS-verified check-in/out timestamps, and tamper-proof legal reporting to avoid probation violations or benefit loss.

2. **The Non-Profit Coordinator Spectrum (Volume vs. Liability)**:
   * **High-Volume Operations Coordinators (Marcus)**: Managing food banks, community cleanups, and warehouse packaging lines. Battles a chronic 30–50% volunteer no-show rate and needs fast multi-site shift coordination.
   * **Specialized & High-Liability Program Directors (Dr. Sarah)**: Managing youth mentorship, foster care, and 24/7 crisis support lines. Legally cannot allow "just anyone off the street" near vulnerable populations. Requires mandatory background check vetting, certified training gates, and credential expiration tracking.

3. **The Civic Governance Authority**:
   * **Civic Regional Moderators (Elena)**: Municipal leaders responsible for public safety, territorial boundary integrity, and 48-hour SLA turnaround.

```mermaid
graph TD
    subgraph "1. The Volunteer Spectrum (Motivations & Commitments)"
        V1["<b>Maya Lin (24) — Spontaneous Local</b><br/>• Low barrier, 2-3 hr slots<br/>• Sub-50ms JTE discovery<br/>• Walk-ins (OPEN_DOOR)"]
        V2["<b>David Chen (31) — Purpose & Skill-Building</b><br/>• High commitment, 5-10 hrs/wk<br/>• Skill & Cause Taxonomy<br/>• Regional Initiatives (REGIONAL)"]
        V3["<b>Chloe Torres (17) — Academic & Portfolio</b><br/>• 100-hour college & NHS milestones<br/>• Group reservation holds (Key Club)<br/>• Verified Common App PDF transcript"]
        V4["<b>Raymond Miller (35) — Court & SNAP Mandated</b><br/>• Court diversion & SNAP work hours<br/>• Explicit eligibility filters<br/>• Tamper-proof court-admissible logs"]
    end

    subgraph "2. The Coordinator Spectrum"
        C1["<b>Marcus Vance (38) — High-Volume Operations</b><br/>• Food bank packaging, cleanups, sorting<br/>• Fights 30-50% no-shows<br/>• 1:N project_locations & automated waitlists"]
        C2["<b>Dr. Sarah Al-Mansoor (42) — High-Liability Programs</b><br/>• Youth, foster care, crisis lines<br/>• Mandatory background checks<br/>• Gated shift qualification state"]
    end

    subgraph "3. Civic Governance & Safeguarding"
        G1["<b>Elena Rostova (45) — Civic Regional Moderator</b><br/>• County public safety & municipal authority<br/>• PostGIS containment & 48h SLA escalation"]
    end

    V1 & V4 <-->|Public JTE Web Explorer & Compliance Flags| C1
    V2 & V3 <-->|Compose Multiplatform & Skill/Group Tags| C2
    C1 & C2 <-->|Regional Moderation Queue| G1
```

---

## 1. Maya Lin — The Spontaneous Local Volunteer

### Proto-Persona Profile

* **Archetype**: The Spontaneous Local Volunteer (`STANDARD_USER` / Public Searcher)
* **Demographics**: 24 years old, Marketing Specialist & Part-time Graduate Student. Urban apartment resident, public transit & bicycle commuter.
* **Context**: Maya wants to contribute to her local community and fulfill a 20-hour service requirement for her degree. Juggling corporate work and night classes, her availability is constrained to specific 2-to-3-hour weekend windows. She has zero patience for administrative paperwork, mandatory app downloads, or account creation walls just to view event details.
* **Tech Environment**: iPhone 15 on cellular data / transit Wi-Fi. High sensitivity to web page weight and battery drain.

### 4-Quadrant Empathy Map

| Quadrant | Observed & Internalized Insights |
| :--- | :--- |
| **SAYS** *(Verbal Quotes)* | |
| **THINKS** *(Internalized Beliefs)* | • *💭 "I hope this isn't disorganized like the last cleanup where we stood around for 45 minutes waiting for tools."*<br/>• *💭 "Is this neighborhood safe to bike to early on a Saturday morning? Where do I safely park?"*<br/>• *💭 "Will anyone actually notice or care if I show up, or am I just an extra body in a corner?"*<br/>• *💭 "I really want to help my community, but I cannot commit to a recurring 6-month contract."* |
| **DOES** *(Observable Behaviors)* | |
| **FEELS** *(Affective State)* | • ❤️ **Eager**: Genuinely motivated to make an authentic community impact and meet neighborhood peers.<br/>• ❤️ **Anxious**: Apprehensive about arrival logistics, unfamiliar venues, and parking confusion.<br/>• ❤️ **Impatient**: Irritated by sluggish web interfaces, infinite spinners, and redundant forms.<br/>• ❤️ **Proud**: Uplifted and empowered when her volunteer contribution produces tangible results. |

### Critical Pains & Desired Gains

* **Pains**:
  1. *Phantom Shifts*: Traveling across town only to find a cancelled event or an overbooked room.
  2. *Sluggish Web Performance*: Multi-megabyte JS/Wasm bundles freezing mobile browsers on transit.
  3. *Inaccurate Geocoding*: Map pins pointing to corporate mailing offices rather than active field sites.
* **Gains**:
  1. *Sub-50ms Instant Browsing*: Frictionless public web discovery with zero JavaScript bundle overhead.
  2. *Truthful Driving Distance*: Real PostGIS geodesic distance with clear site entry instructions.
  3. *Verified Digital Credential*: One-click certified service hour records for school and employer.
---


## 2. David Chen — The Purpose-Driven & Skill-Building Volunteer

### Proto-Persona Profile

* **Archetype**: The Purpose-Driven & Skill-Building Volunteer (`STANDARD_USER` / Specialist)
* **Demographics**: 31 years old, Data Analyst transitioning to ESG & Non-Profit Tech. Metro area resident.
* **Context**: David has stable employment and wants to dedicate 5–10 hours every week to causes he is deeply passionate about (wildlife conservation, climate resilience, and youth STEM education). He wants to build real-world leadership experience and apply professional skills (data engineering, GIS mapping, project management) to charitable initiatives. He is completely willing to undergo background checks, attend a 4-hour orientation, and commit to long-term schedules.
* **Tech Environment**: MacBook Pro + Pixel Phone. Advanced searcher who values rich filtering and transparency.

### 4-Quadrant Empathy Map

| Quadrant | Observed & Internalized Insights |
| :--- | :--- |
| **SAYS** *(Verbal Quotes)* | |
| **THINKS** *(Internalized Beliefs)* | • *💭 "I don't want to waste my Saturdays doing manual busywork that a machine or general volunteer could do in 5 minutes."*<br/>• *💭 "If this organization is disorganized during the onboarding process, their field operations will probably be chaotic too."*<br/>• *💭 "I want this service experience on my resume to demonstrate community leadership in clean tech and public data."*<br/>• *💭 "I hope they respect my professional time and don't cancel specialized shifts after I've blocked out my calendar."* |
| **DOES** *(Observable Behaviors)* | |
| **FEELS** *(Affective State)* | • ❤️ **Purpose-Driven**: Deeply compelled by moral mission, environmental stewardship, and community impact.<br/>• ❤️ **Selective**: Unwilling to squander finite professional bandwidth on chaotic, low-impact tasks.<br/>• ❤️ **Committed**: Highly dependable, accountable, and steadfast once aligned with a competent leadership team.<br/>• ❤️ **Fulfilled**: Deeply energized when high-skill contributions transform a non-profit's operational capacity. |

### Critical Pains & Desired Gains

* **Pains**:
  1. *Skill Underutilization*: Being relegated to manual busywork despite possessing high-demand technical capabilities.
  2. *Distance-Only Search*: Physical radius filters that exclude high-relevance regional initiatives across county borders.
  3. *Redundant Screening*: Repeating background checks and reference interviews for every new organization.
* **Gains**:
  1. *Deep Skill & Cause Taxonomy*: Fast faceted filtering by required competencies (`#gis`, `#cpr`) and causes (`#wildlife`).
  2. *Regional Initiative Access*: Joining geofenced polygon projects (`REGIONAL` STI) with remote or field components.
  3. *Verified Credential Passport*: Reusable digital clearances unlocking specialized, high-trust shifts instantly.
---


## 3. Chloe Torres — The Academic & Portfolio-Building Student Volunteer

### Proto-Persona Profile

* **Archetype**: The Academic & Portfolio-Building Student Volunteer (`STANDARD_USER` — Student / Club Officer)
* **Demographics**: 17 years old, High School Junior, Key Club Philanthropy Officer & National Honor Society candidate.
* **Context**: Chloe is preparing competitive college and scholarship applications, aiming for a minimum of 100 verified service hours and leadership distinction. As a club officer, she coordinates weekend service events for 15–20 high school classmates. She needs indisputable documentation of her hours, supervisor sign-offs, group slot holds, and an exportable certified service transcript for university portals (e.g. Common Application).
* **Tech Environment**: iPhone 14 & School Chromebook. Mobile native living on Instagram, TikTok, and Google Classroom.

### 4-Quadrant Empathy Map

| Quadrant | Observed & Internalized Insights |
| :--- | :--- |
| **SAYS** *(Verbal Quotes)* | |
| **THINKS** *(Internalized Beliefs)* | • *💭 "If this non-profit doesn't verify my hours before the deadline, all my weekend work won't count on my application."*<br/>• *💭 "I hope our club members actually show up so I don't look unreliable in front of the volunteer coordinator."*<br/>• *💭 "I want to do real leadership work that looks impressive on my college resume, not just hand out flyers."*<br/>• *💭 "I need an easy way to export an official service transcript to upload directly to the Common App."* |
| **DOES** *(Observable Behaviors)* | |
| **FEELS** *(Affective State)* | • ❤️ **Ambitious**: Highly driven to achieve scholarship milestones and build an impressive civic portfolio.<br/>• ❤️ **Anxious**: Stressed about tight application deadlines and chasing down slow supervisor signatures.<br/>• ❤️ **Accountable**: Feels strong peer pressure to deliver organized, safe service opportunities for her club.<br/>• ❤️ **Proud**: Uplifted and validated when her certified transcript reaches the 100-hour achievement tier. |

### Critical Pains & Desired Gains

* **Pains**:
  1. *Lost Paper Records*: Paper logs misplaced by counselors or organizers destroying months of documented service.
  2. *Group Booking Gaps*: Having to ask 15 teenagers to individually sign up before slots fill up.
  3. *Delayed Verification*: Non-profits taking weeks to confirm hours, causing students to miss scholarship deadlines.
* **Gains**:
  1. *Digital Service Transcript*: Instant export of certified, tamper-proof service records for college portals.
  2. *Group Reservation Holds*: Reserving slot blocks with shareable invite tokens for student clubs.
  3. *Digital Minor Consent*: Automated parental e-signatures eliminating physical paper slips.
---


## 4. Raymond Miller — The Compliance & Mandated Service Volunteer

### Proto-Persona Profile

* **Archetype**: The Compliance & Mandated Service Volunteer (`STANDARD_USER` — Court / SNAP Mandated)
* **Demographics**: 35 years old, Hourly warehouse employee balancing a 40-hour court-ordered community restitution requirement with monthly 20-hour SNAP ABAWD work requirements.
* **Context**: Raymond must complete community service to avoid court sanctions (probation violation/fines) and maintain his family's SNAP food assistance benefits. He relies on public bus transit and hourly wages, meaning he cannot miss work and must find flexible evening or weekend service. His freedom and nutritional benefits depend on strictly compliant, tamper-proof, court-admissible documentation.
* **Tech Environment**: Android smartphone on prepaid cellular plan; public library computers for printing. Obsessively monitors deadlines and hour countdowns.

### 4-Quadrant Empathy Map

| Quadrant | Observed & Internalized Insights |
| :--- | :--- |
| **SAYS** *(Verbal Quotes)* | |
| **THINKS** *(Internalized Beliefs)* | • *💭 "If I lose this food assistance or miss my court hours, my whole family will suffer."*<br/>• *💭 "I feel judged when people ask why I'm volunteering; I just want to do honest hard work and clear my record."*<br/>• *💭 "I hope the probation officer accepts this digital report without demanding a wet-ink rubber stamp."*<br/>• *💭 "I can't afford to waste bus fare traveling to a site that turns me away at the door."* |
| **DOES** *(Observable Behaviors)* | |
| **FEELS** *(Affective State)* | • ❤️ **Pressured**: Constantly aware of legal deadlines and the severe consequences of non-compliance.<br/>• ❤️ **Vulnerable**: Afraid of bureaucratic delays causing benefit termination or probation revocation.<br/>• ❤️ **Dignified**: Proud of doing genuine, productive hard work to repay his civic obligation.<br/>• ❤️ **Relieved**: Deeply grateful when verified hours are logged cleanly and court requirements are fulfilled. |

### Critical Pains & Desired Gains

* **Pains**:
  1. *Rejection at Venue*: Being turned away at the door because an organization silently bars court-mandated volunteers.
  2. *Probation Non-Compliance*: Paper logs rejected for formatting technicalities, risking bench warrants or fines.
  3. *Benefit Lapses*: Delays in monthly hour reporting causing SNAP food assistance to be abruptly suspended.
* **Gains**:
  1. *Compliance Search Filter*: Clear visibility into court-approved and SNAP-eligible non-profit opportunities.
  2. *GPS-Verified Check-in*: Incontestable timestamped attendance logs with supervisor verification IDs.
  3. *Court-Admissible Reports*: Official PDF summaries with organization tax IDs and verification tokens for judges.
---


## 5. Marcus Vance — The High-Volume Operations Coordinator

### Proto-Persona Profile

* **Archetype**: The High-Volume Operations Coordinator (`ORG_ADMIN` / `ORG_MANAGER`)
* **Demographics**: 38 years old, Volunteer Operations Coordinator at Metropolitan Food Relief. Works out of regional headquarters and warehouse distribution hubs.
* **Context**: Marcus manages logistics for 600+ monthly volunteer slots across 5 physical distribution warehouses and seasonal farm gleaning sites. Operating with a lean team, he spends excessive hours re-entering data between paper clipboards, Excel sheets, and municipal permits, all while battling a demoralizing 30–50% volunteer no-show rate.
* **Tech Environment**: Desktop power user in the office; field tablet user on-site. Needs keyboard-driven batch tools and instant CSV syncing.

### 4-Quadrant Empathy Map

| Quadrant | Observed & Internalized Insights |
| :--- | :--- |
| **SAYS** *(Verbal Quotes)* | |
| **THINKS** *(Internalized Beliefs)* | • *💭 "If volunteers don't show up on Saturday morning, vulnerable families don't get food boxes this week."*<br/>• *💭 "I got into non-profit work to serve my community, but 70% of my time is spent on clerical data entry."*<br/>• *💭 "I hope our regional moderation approval doesn't get stuck in civic review limbo before this weekend's drive."*<br/>• *💭 "We desperately need a core cohort of vetted regulars rather than an endless churn of one-off tourists."* |
| **DOES** *(Observable Behaviors)* | |
| **FEELS** *(Affective State)* | • ❤️ **Chronically Overwhelmed**: Constantly buried under administrative repetition, roster churn, and spreadsheets.<br/>• ❤️ **Anxious**: Stressed on event mornings about shift capacity shortfalls and critical assembly bottlenecks.<br/>• ❤️ **Protective**: Passionately committed to organizational liability compliance and volunteer physical safety.<br/>• ❤️ **Mission-Driven**: Deeply gratified when distribution lines run smoothly and food reaches community members. |

### Critical Pains & Desired Gains

* **Pains**:
  1. *30–50% No-Show Rates*: Chronic volunteer absenteeism crippling physical warehouse and harvest operations.
  2. *Duplicate Data Entry*: Redundant project cloning across multiple physical addresses and distribution nodes.
  3. *Liability Vulnerability*: Paper waivers lost or misplaced, risking catastrophic liability in injury claims.
* **Gains**:
  1. *Automated Waitlist Backfill*: Real-time promotion of standby volunteers when cancellations occur.
  2. *1-to-Many Project Locations*: Creating one umbrella project linked to multiple physical locations.
  3. *Digital Roster Check-In*: QR scanning and geofenced check-in eliminating paper clipboard transcription.
---


## 6. Dr. Sarah Al-Mansoor — The High-Liability Program Director

### Proto-Persona Profile

* **Archetype**: The High-Liability & Safeguarding Director (`ORG_ADMIN` — Youth & Crisis)
* **Demographics**: 42 years old, Program Director at Youth Horizons & Crisis Intervention. Secure government-compliant workstation and encrypted laptop.
* **Context**: Dr. Sarah oversees court-mandated youth mentoring, transitional foster youth housing, and a 24/7 crisis intervention hotline. Her programs involve direct contact with vulnerable minors and individuals in trauma. She operates under strict state child protection laws, HIPAA/privacy rules, and insurance mandates. She cannot permit unvetted public sign-ups and requires rigorous background check pipelines, training certifications, and credential expiration tracking.
* **Tech Environment**: Strict compliance focus: Role-Based Access Control (RBAC), multi-factor authentication, background check API integration, and audit reports.

### 4-Quadrant Empathy Map

| Quadrant | Observed & Internalized Insights |
| :--- | :--- |
| **SAYS** *(Verbal Quotes)* | |
| **THINKS** *(Internalized Beliefs)* | • *💭 "A single safeguarding failure or unvetted individual could cause catastrophic harm to a child and destroy our mission."*<br/>• *💭 "I feel guilty turning away eager people, but our duty of care to vulnerable clients must always come first."*<br/>• *💭 "I waste thousands of dollars in background check fees on volunteers who ghost us during training."*<br/>• *💭 "I need an automated system that locks shifts until all credentials, trainings, and waivers are verified."* |
| **DOES** *(Observable Behaviors)* | |
| **FEELS** *(Affective State)* | • ❤️ **Vigilant**: Hyper-aware of legal liability, youth safeguarding, and institutional compliance.<br/>• ❤️ **Protective**: Fiercely devoted to shielding trauma survivors and minors from physical or emotional harm.<br/>• ❤️ **Cautious**: Skeptical of "instant sign-up" features that sacrifice safety for user acquisition metrics.<br/>• ❤️ **Relieved**: Deeply reassured when technical guardrails guarantee that only 100% vetted volunteers access shifts. |

### Critical Pains & Desired Gains

* **Pains**:
  1. *Safeguarding Breach Risk*: Unauthorized or unvetted individuals gaining access to vulnerable populations.
  2. *Expired Credential Blind Spots*: Volunteers working with lapsed background checks or certifications.
  3. *Wasted Screening Capital*: Paying expensive background check fees for flaky volunteers who abandon the program.
* **Gains**:
  1. *Automated Qualification Gating*: Shifts automatically locked until background checks and training are certified.
  2. *Integrated Screening Pipeline*: Direct API verification of background clearances and identity records.
  3. *Audit-Ready Compliance Reports*: 1-click export of tamper-proof credential logs for grant and legal authorities.
---


## 7. Elena Rostova — The Civic Regional Moderator

### Proto-Persona Profile

* **Archetype**: The Civic Regional Moderator (`REGION_DIRECTOR` / `REGION_AGENT`)
* **Demographics**: 45 years old, County Director of Civic Engagement & Public Safety. Works at the County Government Center.
* **Context**: Elena oversees municipal volunteer services, community engagement initiatives, and public safety vetting across a regional jurisdiction covering four municipalities. She is legally responsible for ensuring that all published volunteer opportunities adhere to municipal safety codes, insurance requirements, and non-profit validity. She must balance thorough civic oversight with strict 48-hour SLA turnaround times.
* **Tech Environment**: GIS Power User requiring layered map visualizations (parcels, municipal boundaries, flood zones). Audit-centric inspector verifying historical logs, timestamp diffs, and actor IDs. Strict workflow compliance enforcer.

### 4-Quadrant Empathy Map

| Quadrant | Observed & Internalized Insights |
| :--- | :--- |
| **SAYS** *(Verbal Quotes)* | |
| **THINKS** *(Internalized Beliefs)* | • *💭 "If an unvetted group runs a hazardous project and a citizen gets injured, the city and platform bear liability."*<br/>• *💭 "I don't want to be the bureaucratic bottleneck holding back passionate neighborhood volunteers."*<br/>• *💭 "Is this project truly a public charity initiative, or is a private commercial business seeking free labor?"*<br/>• *💭 "I need clear, binding PostGIS polygon containment checks, not vague text descriptions of service territories."* |
| **DOES** *(Observable Behaviors)* | |
| **FEELS** *(Affective State)* | • ❤️ **Accountable**: Strongly weighs the legal, ethical, and public safety responsibilities of municipal governance.<br/>• ❤️ **Pressured**: Constantly aware of the ticking 48-hour SLA escalation clock and heavy seasonal volume.<br/>• ❤️ **Vigilant**: Skeptical of ambiguous descriptions, missing safety precautions, and unverified organizations.<br/>• ❤️ **Gratified**: Proud when well-governed civic volunteer initiatives mobilize hundreds of residents safely. |

### Critical Pains & Desired Gains

* **Pains**:
  1. *Boundary Ambiguity*: Cross-jurisdictional projects lacking clear municipal liability boundaries.
  2. *Stealth Edits*: Organizers altering project scope post-approval without triggering municipal re-review.
  3. *SLA Queue Bottlenecks*: Ticking 48-hour escalation timers causing operational stress and review backlogs.
* **Gains**:
  1. *Automated Spatial Routing*: PostGIS boundary intersection automatically assigning projects to the correct region.
  2. *Immutable Audit Trails*: Append-only transaction records capturing actor, timestamp, and status diff.
  3. *Automated 48h Escalation*: Scheduled partial index job escalating stalled projects without manual tracking.

---

## The 8-Page Draw.io Diagram Deliverable

The visual model is stored at:
[`docs/human-strategy-personas.drawio`](./human-strategy-personas.drawio)

### Diagram Breakdown

* **Page 1: `1. Human Strategy Ecosystem`** — Macro mapping of all 7 personas across the volunteer and coordinator spectrums, 4 technical architecture layers, and 4 human value loops.
* **Page 2: `2. Maya - Spontaneous Local Volunteer`** — Proto-persona card + 4-quadrant Empathy Map + Pains/Gains.
* **Page 3: `3. David - Purpose & Skill Volunteer`** — Proto-persona card + 4-quadrant Empathy Map + Pains/Gains.
* **Page 4: `4. Chloe - Academic & Portfolio Volunteer`** — Proto-persona card + 4-quadrant Empathy Map + Pains/Gains.
* **Page 5: `5. Raymond - Mandated & Compliance Volunteer`** — Proto-persona card + 4-quadrant Empathy Map + Pains/Gains.
* **Page 6: `6. Marcus - High-Volume Coordinator`** — Proto-persona card + 4-quadrant Empathy Map + Pains/Gains.
* **Page 7: `7. Dr. Sarah - Specialized Director`** — Proto-persona card + 4-quadrant Empathy Map + Pains/Gains.
* **Page 8: `8. Elena - Civic Regional Moderator`** — Proto-persona card + 4-quadrant Empathy Map + Pains/Gains.

### How to Open & Inspect

* **In VS Code / Cursor**: Click on [`docs/human-strategy-personas.drawio`](./human-strategy-personas.drawio) using the *Draw.io Integration* extension (`hediet.vscode-drawio`).
* **In Web Browser**: Open [app.diagrams.net](https://app.diagrams.net/) and drag-and-drop the file.

---

## Document Compilation & PDF Export Options

### 1. Publication-Grade Asciidoctor PDF (Recommended)

The primary, publication-grade document generator runs natively on the JVM via Gradle:

```bash
./gradlew asciidoctorPdf
```

* **Output**: `build/docs/asciidoctor-pdf/human-strategy.pdf`
* **Features**: Formats with formal chapter titling, cover page, paginated table of contents with dot leaders, callout icons, and clean table borders.

### 2. Browser-Based CSS PDF (Experimental)

The document can also be converted using browser-based CSS via the Chromium/Puppeteer engine (`asciidoctor-web-pdf`):

```bash
# Via PowerShell
./scripts/render-web-pdf.ps1

# Via Bash
./scripts/render-web-pdf.sh

# Or directly via npx
npx asciidoctor-pdf docs/human-strategy.adoc -B docs -D build/docs/asciidoctor-web-pdf --preserve-html
```

* **Output**: `build/docs/asciidoctor-web-pdf/human-strategy.pdf`
* **Caveats**: While this pipeline leverages web standards and CSS (`styles/human-strategy.css`), the output **does not look as good** as the native Asciidoctor PDF engine (lacks automatic TOC page numbering/leaders, table borders and column widths can be uneven, and page breaks are less predictable). To keep the backend Gradle build lean, this task is maintained as an auxiliary script outside `build.gradle.kts`.

