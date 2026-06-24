package lol.pbu.kaiju.core.controller

import groovy.sql.Sql
import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.core.domain.Tag
import lol.pbu.kaiju.core.repository.TagRepository
import net.datafaker.Faker
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

@MicronautTest
class TagControllerSpec extends Specification {

    @Inject
    @Shared
    Connection connection

    @Shared
    Connection standaloneConnection

    @Shared
    Sql sql

    @Inject
    TagRepository tagRepository

    @Inject
    TagController tagController

    @Shared
    Faker faker = new Faker()

    def setupSpec() {
        standaloneConnection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/volunteer_monster", "jimmy", "warm-farts-smell-worse")
        sql = new Sql((Connection) Proxy.newProxyInstance(
                Connection.class.classLoader,
                [Connection.class] as Class[],
                { Object proxy, Method method, Object[] args ->
                    try {
                        return method.invoke(connection, args)
                    } catch (Throwable ignored) {
                        return method.invoke(standaloneConnection, args)
                    }
                } as InvocationHandler
        ))
        standaloneConnection.createStatement().execute("INSERT INTO tags (name) VALUES ('test-tag-a')")
        standaloneConnection.createStatement().execute("INSERT INTO tags (name) VALUES ('test-tag-b')")
    }

    def cleanupSpec() {
        standaloneConnection.createStatement().execute("DELETE FROM tags WHERE name LIKE 'test-tag-%'")
        standaloneConnection?.close()
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid tag"() {
        given: "a new valid tag"
        def newTag = new Tag(
                null,
                "tag-${faker.lorem().word()}-${UUID.randomUUID().toString().substring(0, 8)}"
        )

        when: "the tag is added"
        Tag saved = tagController.addTag(newTag)

        then: "the tag is persisted with a generated ID"
        verifyAll {
            saved.id() != null
            saved.name() == newTag.name()
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM tags WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == name
        }
    }

    @Unroll
    def "CREATE | should fail to save tag with invalid data: #testCase"(String testCase, Tag tag) {
        when: "an attempt is made to add a tag with invalid data"
        tagController.addTag(tag)

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, tag] << {
            def validData = [
                    name: "valid-tag"
            ]

            def invalidCases = [
                    [field: 'name', value: null, caseName: "Null Name"],
                    [field: 'name', value: ' ', caseName: "Blank Name"],
                    [field: 'name', value: 'A' * 51, caseName: "Name Too Long"]
            ]

            return invalidCases.collect { invalidCase ->
                def props = new HashMap(validData)
                props[invalidCase.field] = invalidCase.value
                def t = new Tag(
                        null,
                        props.name as String
                )
                [invalidCase.caseName, t]
            }
        }()
    }

    /********** READ Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "READ | should retrieve an existing tag by ID: #name"(UUID id, String name) {
        when: "the tag is requested by its ID"
        def result = tagController.getTag(id)

        then: "the correct tag is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
            result.get().name() == name
        }

        where:
        [id, name] << sql.rows("SELECT id, name FROM tags LIMIT 3").collect { [it.id, it.name] }
    }

    def "READ | should return empty for a non-existent tag ID"() {
        when: "a non-existent tag is requested"
        def result = tagController.getTag(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "UPDATE | should successfully update an existing tag: #originalName"(UUID id, String originalName) {
        given: "an existing tag's details"
        def newName = "updated-${faker.lorem().word()}-${UUID.randomUUID().toString().substring(0, 8)}"
        def updateRequest = new Tag(null, newName)

        when: "the tag is updated"
        Tag updated = tagController.updateTag(id, updateRequest)

        then: "the returned tag contains the updated data"
        verifyAll {
            updated.id() == id
            updated.name() == newName
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT name FROM tags WHERE id = ?", [id])
        verifyAll(dbResult) {
            name == newName
        }

        where:
        [id, originalName] << sql.rows("SELECT id, name FROM tags LIMIT 2").collect { [it.id, it.name] }
    }

    def "UPDATE | should fail to update a non-existent tag"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new Tag(null, "new-tag")

        when: "an update is attempted"
        tagController.updateTag(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing tag"() {
        given: "a new tag to be deleted"
        def tempTag = new Tag(
                null,
                "temporary-tag-to-delete"
        )
        def saved = tagController.addTag(tempTag)
        UUID id = saved.id()
        assert tagRepository.existsById(id)

        when: "the tag is deleted"
        tagController.deleteTag(id)

        then: "the tag no longer exists in the repository or database"
        verifyAll {
            !tagRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM tags WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should handle deletion of non-existent tag gracefully"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted"
        tagController.deleteTag(nonExistentId)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** LIST Tests **********/

    def "LIST | should fully drain all tags sequentially using cursors"() {
        setup:
        Set<Tag> allTags = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("name")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<Tag> page = tagController.getTags(pageable)
            allTags.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all tags from the database"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM tags").count
        verifyAll {
            allTags.size() == totalCount
            allTags.size() >= 2
        }
    }
}
