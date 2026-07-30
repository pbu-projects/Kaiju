package lol.pbu

import io.micronaut.runtime.EmbeddedApplication
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import spock.lang.Specification
import jakarta.inject.Inject
import javax.sql.DataSource

@MicronautTest
class KaijuSpec extends Specification {

    @Inject
    EmbeddedApplication<?> application

    @Inject
    DataSource dataSource

    void 'test application starts'() {
        expect:
        application.running
    }

    void 'test database container is populated with schema and seed data'() {
        when:
        def connection = dataSource.connection
        def statement = connection.createStatement()

        def userRs = statement.executeQuery("SELECT COUNT(*) FROM users")
        userRs.next()
        int userCount = userRs.getInt(1)

        def projectRs = statement.executeQuery("SELECT COUNT(*) FROM projects")
        projectRs.next()
        int projectCount = projectRs.getInt(1)

        def postgisRs = statement.executeQuery("SELECT ST_AsText(geom) FROM locations LIMIT 1")
        postgisRs.next()
        String pointWkt = postgisRs.getString(1)

        then:
        userCount > 0
        projectCount > 0
        pointWkt != null
        pointWkt.startsWith("POINT")
    }
}
