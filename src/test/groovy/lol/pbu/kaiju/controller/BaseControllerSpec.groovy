package lol.pbu.kaiju.controller

import groovy.sql.Sql
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import javax.sql.DataSource
import spock.lang.Shared
import spock.lang.Specification

@MicronautTest(transactional = true)
class BaseControllerSpec extends Specification {

    @Inject
    @Shared
    DataSource dataSource

    @Shared
    DataSource rawDataSource

    def setupSpec() {
        rawDataSource = dataSource.hasProperty('targetDataSource') ? (DataSource) dataSource.targetDataSource : dataSource
    }

    Sql getSql() {
        try {
            def conn = dataSource.getConnection()
            conn.close()
            return new Sql(dataSource)
        } catch (Exception ignored) {
            return new Sql(rawDataSource)
        }
    }

    protected void executeUpdate(String sqlString, Object... parameters) {
        sql.execute(sqlString, parameters as List)
    }
}
