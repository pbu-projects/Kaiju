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
    Sql sql

    def setupSpec() {
        def target = dataSource.hasProperty('targetDataSource') ? dataSource.targetDataSource : dataSource
        sql = new Sql((DataSource) target)
    }

    protected void executeUpdate(String sqlString, Object... parameters) {
        sql.execute(sqlString, parameters as List)
    }
}
