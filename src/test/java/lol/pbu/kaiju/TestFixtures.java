package lol.pbu.kaiju;

import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.model.ProjectType;
import lol.pbu.kaiju.model.VerificationStatus;
import org.locationtech.jts.geom.Point;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class TestFixtures {
    
    public static Organization createDummyOrganization(UUID id, String status) {
        return new Organization(
                id,
                "Test Org",
                null,
                null,
                true,
                VerificationStatus.valueOf(status),
                null,
                null
        );
    }

    public static Location createDummyLocation(Point geom) {
        return new Location(
                UUID.randomUUID(),
                "Test",
                "123 Main",
                "Denver",
                "CO",
                "80202",
                "US",
                geom
        );
    }

    public static Project createDummyProject(Organization org, Location location) {
        return new Project(
                UUID.randomUUID(),
                org,
                null,
                "Title",
                "Desc",
                ProjectType.STANDARD,
                ProjectStatus.PENDING,
                OffsetDateTime.now(),
                null,
                null,
                List.of(location),
                null
        );
    }
}
