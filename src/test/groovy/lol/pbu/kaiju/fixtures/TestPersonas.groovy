package lol.pbu.kaiju.fixtures

import groovy.transform.CompileStatic
import lol.pbu.kaiju.model.OrganizationUserRole
import lol.pbu.kaiju.model.RegionUserRole
import lol.pbu.kaiju.model.UserRole

@CompileStatic
class TestPersonas {

    // Regional Hierarchy IDs
    static final UUID COLORADO_STATE_REGION_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001")
    static final UUID DENVER_COUNTY_REGION_ID = UUID.fromString("a0000000-0000-0000-0000-000000000002")
    static final UUID EL_PASO_COUNTY_REGION_ID = UUID.fromString("a0000000-0000-0000-0000-000000000003")
    static final UUID DENVER_DOWNTOWN_REGION_ID = UUID.fromString("a0000000-0000-0000-0000-000000000004")
    static final UUID COLORADO_SPRINGS_DOWNTOWN_REGION_ID = UUID.fromString("a0000000-0000-0000-0000-000000000005")

    // Organizational Hierarchy IDs
    static final UUID NATIONAL_RELIEF_NETWORK_ID = UUID.fromString("b0000000-0000-0000-0000-000000000001")
    static final UUID COLORADO_STATE_CHAPTER_ID = UUID.fromString("b0000000-0000-0000-0000-000000000002")
    static final UUID UTAH_STATE_CHAPTER_ID = UUID.fromString("b0000000-0000-0000-0000-000000000003")
    static final UUID DENVER_METRO_BRANCH_ID = UUID.fromString("b0000000-0000-0000-0000-000000000004")
    static final UUID COLORADO_SPRINGS_BRANCH_ID = UUID.fromString("b0000000-0000-0000-0000-000000000005")
    static final UUID LOCAL_GRASSROOTS_COOP_ID = UUID.fromString("b0000000-0000-0000-0000-000000000006")

    static class Persona {
        final UUID id
        final String email
        final String displayName
        final UserRole systemRole
        final UUID regionId
        final RegionUserRole regionRole
        final UUID organizationId
        final OrganizationUserRole organizationRole
        final boolean isSeededInDb

        Persona(
                UUID id,
                String email,
                String displayName,
                UserRole systemRole,
                UUID regionId = null,
                RegionUserRole regionRole = null,
                UUID organizationId = null,
                OrganizationUserRole organizationRole = null,
                boolean isSeededInDb = true
        ) {
            this.id = id
            this.email = email
            this.displayName = displayName
            this.systemRole = systemRole
            this.regionId = regionId
            this.regionRole = regionRole
            this.organizationId = organizationId
            this.organizationRole = organizationRole
            this.isSeededInDb = isSeededInDb
        }
    }

    // Global System Admins
    static final Persona SARAH_ADMIN = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000001"),
            "sarah.admin1@kaiju.local",
            "Sarah Admin 1",
            UserRole.GLOBAL_ADMIN
    )
    static final Persona GARY_ADMIN = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000002"),
            "gary.admin2@kaiju.local",
            "Gary Admin 2",
            UserRole.GLOBAL_ADMIN
    )

    // State Region (Level 1)
    static final Persona RACHEL_STATE_DIRECTOR = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000003"),
            "rachel.state.director1@kaiju.local",
            "Rachel State Director 1",
            UserRole.REGION_DIRECTOR,
            COLORADO_STATE_REGION_ID,
            RegionUserRole.REGION_DIRECTOR
    )
    static final Persona ROBERT_STATE_DIRECTOR = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000004"),
            "robert.state.director2@kaiju.local",
            "Robert State Director 2",
            UserRole.REGION_DIRECTOR,
            COLORADO_STATE_REGION_ID,
            RegionUserRole.REGION_DIRECTOR
    )
    static final Persona SAMUEL_STATE_AGENT = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000005"),
            "samuel.state.agent@kaiju.local",
            "Samuel State Agent",
            UserRole.REGION_AGENT,
            COLORADO_STATE_REGION_ID,
            RegionUserRole.REGION_AGENT
    )

    // County Region (Level 2)
    static final Persona DANIEL_COUNTY_DIRECTOR = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000006"),
            "daniel.county.director1@kaiju.local",
            "Daniel County Director 1",
            UserRole.REGION_DIRECTOR,
            DENVER_COUNTY_REGION_ID,
            RegionUserRole.REGION_DIRECTOR
    )
    static final Persona DIANA_COUNTY_DIRECTOR = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000007"),
            "diana.county.director2@kaiju.local",
            "Diana County Director 2",
            UserRole.REGION_DIRECTOR,
            DENVER_COUNTY_REGION_ID,
            RegionUserRole.REGION_DIRECTOR
    )
    static final Persona CONNIE_COUNTY_AGENT = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000008"),
            "connie.county.agent@kaiju.local",
            "Connie County Agent",
            UserRole.REGION_AGENT,
            DENVER_COUNTY_REGION_ID,
            RegionUserRole.REGION_AGENT
    )

    // Sibling County Region (Level 2)
    static final Persona ETHAN_SIBLING_DIRECTOR = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000009"),
            "ethan.sibling.director1@kaiju.local",
            "Ethan Sibling Director 1",
            UserRole.REGION_DIRECTOR,
            EL_PASO_COUNTY_REGION_ID,
            RegionUserRole.REGION_DIRECTOR
    )
    static final Persona EMMA_SIBLING_DIRECTOR = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000010"),
            "emma.sibling.director2@kaiju.local",
            "Emma Sibling Director 2",
            UserRole.REGION_DIRECTOR,
            EL_PASO_COUNTY_REGION_ID,
            RegionUserRole.REGION_DIRECTOR
    )

    // City Region (Level 3)
    static final Persona CLARA_CITY_LEAD = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000011"),
            "clara.city.lead1@kaiju.local",
            "Clara City Lead 1",
            UserRole.REGION_AGENT,
            DENVER_DOWNTOWN_REGION_ID,
            RegionUserRole.REGION_AGENT
    )
    static final Persona CAMERON_CITY_LEAD = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000012"),
            "cameron.city.lead2@kaiju.local",
            "Cameron City Lead 2",
            UserRole.REGION_AGENT,
            DENVER_DOWNTOWN_REGION_ID,
            RegionUserRole.REGION_AGENT
    )

    // National Org (Level 1)
    static final Persona NATHAN_NATIONAL_ADMIN = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000013"),
            "nathan.national.admin1@kaiju.local",
            "Nathan National Admin 1",
            UserRole.STANDARD_USER,
            null,
            null,
            NATIONAL_RELIEF_NETWORK_ID,
            OrganizationUserRole.ORG_ADMIN
    )
    static final Persona NICOLE_NATIONAL_ADMIN = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000014"),
            "nicole.national.admin2@kaiju.local",
            "Nicole National Admin 2",
            UserRole.STANDARD_USER,
            null,
            null,
            NATIONAL_RELIEF_NETWORK_ID,
            OrganizationUserRole.ORG_ADMIN
    )
    static final Persona NOAH_NATIONAL_MANAGER = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000015"),
            "noah.national.manager@kaiju.local",
            "Noah National Manager",
            UserRole.STANDARD_USER,
            null,
            null,
            NATIONAL_RELIEF_NETWORK_ID,
            OrganizationUserRole.ORG_MANAGER
    )

    // State Chapter Org (Level 2)
    static final Persona STEVEN_STATE_ADMIN = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000016"),
            "steven.state.admin1@kaiju.local",
            "Steven State Admin 1",
            UserRole.STANDARD_USER,
            null,
            null,
            COLORADO_STATE_CHAPTER_ID,
            OrganizationUserRole.ORG_ADMIN
    )
    static final Persona SOPHIA_STATE_ADMIN = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000017"),
            "sophia.state.admin2@kaiju.local",
            "Sophia State Admin 2",
            UserRole.STANDARD_USER,
            null,
            null,
            COLORADO_STATE_CHAPTER_ID,
            OrganizationUserRole.ORG_ADMIN
    )
    static final Persona SUSAN_STATE_MANAGER = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000018"),
            "susan.state.manager@kaiju.local",
            "Susan State Manager",
            UserRole.STANDARD_USER,
            null,
            null,
            COLORADO_STATE_CHAPTER_ID,
            OrganizationUserRole.ORG_MANAGER
    )

    // Sibling State Chapter Org (Level 2)
    static final Persona URSULA_UTAH_ADMIN = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000019"),
            "ursula.utah.admin1@kaiju.local",
            "Ursula Utah Admin 1",
            UserRole.STANDARD_USER,
            null,
            null,
            UTAH_STATE_CHAPTER_ID,
            OrganizationUserRole.ORG_ADMIN
    )
    static final Persona ULYSSES_UTAH_ADMIN = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000020"),
            "ulysses.utah.admin2@kaiju.local",
            "Ulysses Utah Admin 2",
            UserRole.STANDARD_USER,
            null,
            null,
            UTAH_STATE_CHAPTER_ID,
            OrganizationUserRole.ORG_ADMIN
    )

    // Local Branch Org (Level 3)
    static final Persona LEO_LOCAL_ADMIN = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000021"),
            "leo.local.admin1@kaiju.local",
            "Leo Local Admin 1",
            UserRole.STANDARD_USER,
            null,
            null,
            DENVER_METRO_BRANCH_ID,
            OrganizationUserRole.ORG_ADMIN
    )
    static final Persona LISA_LOCAL_ADMIN = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000022"),
            "lisa.local.admin2@kaiju.local",
            "Lisa Local Admin 2",
            UserRole.STANDARD_USER,
            null,
            null,
            DENVER_METRO_BRANCH_ID,
            OrganizationUserRole.ORG_ADMIN
    )
    static final Persona LAURA_LOCAL_MANAGER = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000023"),
            "laura.local.manager@kaiju.local",
            "Laura Local Manager",
            UserRole.STANDARD_USER,
            null,
            null,
            DENVER_METRO_BRANCH_ID,
            OrganizationUserRole.ORG_MANAGER
    )
    static final Persona LUKE_LOCAL_MEMBER = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000024"),
            "luke.local.member@kaiju.local",
            "Luke Local Member",
            UserRole.STANDARD_USER,
            null,
            null,
            DENVER_METRO_BRANCH_ID,
            OrganizationUserRole.ORG_MEMBER
    )

    // Independent Volunteers
    static final Persona VICTOR_VOLUNTEER = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000025"),
            "victor.volunteer1@kaiju.local",
            "Victor Volunteer 1",
            UserRole.STANDARD_USER
    )
    static final Persona VALERIE_VOLUNTEER = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000026"),
            "valerie.volunteer2@kaiju.local",
            "Valerie Volunteer 2",
            UserRole.STANDARD_USER
    )

    // Just-In-Time Provisioning Candidate (Not seeded in DB)
    static final Persona JENNY_NEW = new Persona(
            UUID.fromString("c0000000-0000-0000-0000-000000000099"),
            "jenny.new@kaiju.local",
            "Jenny New",
            UserRole.STANDARD_USER,
            null,
            null,
            null,
            null,
            false
    )

    static final List<Persona> ALL_PERSONAS = List.of(
            SARAH_ADMIN, GARY_ADMIN,
            RACHEL_STATE_DIRECTOR, ROBERT_STATE_DIRECTOR, SAMUEL_STATE_AGENT,
            DANIEL_COUNTY_DIRECTOR, DIANA_COUNTY_DIRECTOR, CONNIE_COUNTY_AGENT,
            ETHAN_SIBLING_DIRECTOR, EMMA_SIBLING_DIRECTOR,
            CLARA_CITY_LEAD, CAMERON_CITY_LEAD,
            NATHAN_NATIONAL_ADMIN, NICOLE_NATIONAL_ADMIN, NOAH_NATIONAL_MANAGER,
            STEVEN_STATE_ADMIN, SOPHIA_STATE_ADMIN, SUSAN_STATE_MANAGER,
            URSULA_UTAH_ADMIN, ULYSSES_UTAH_ADMIN,
            LEO_LOCAL_ADMIN, LISA_LOCAL_ADMIN, LAURA_LOCAL_MANAGER, LUKE_LOCAL_MEMBER,
            VICTOR_VOLUNTEER, VALERIE_VOLUNTEER,
            JENNY_NEW
    )

    static final List<Persona> SEEDED_PERSONAS = ALL_PERSONAS.findAll { it.isSeededInDb }

    static Persona findByEmail(String email) {
        ALL_PERSONAS.find { it.email.equalsIgnoreCase(email) }
    }

    static Persona findById(UUID id) {
        ALL_PERSONAS.find { it.id == id }
    }
}
