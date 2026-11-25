package lol.pbu.kaiju.domain

import lol.pbu.kaiju.model.UserRole
import spock.lang.Specification

import java.time.OffsetDateTime
import java.time.ZoneOffset

class UserSpec extends Specification {

    def "withCreationDetails keeps existing role and createdAt when present"() {
        given: "a user with explicit role and createdAt"
        OffsetDateTime existingTime = OffsetDateTime.of(2026, 1, 10, 8, 0, 0, 0, ZoneOffset.UTC)
        OffsetDateTime defaultTime = OffsetDateTime.of(2026, 2, 10, 8, 0, 0, 0, ZoneOffset.UTC)
        User user = new User(UUID.randomUUID(), "Raw@Example.com", UserRole.REGION_DIRECTOR, existingTime)

        when: "applying creation details"
        User result = user.withCreationDetails("raw@example.com", UserRole.STANDARD_USER, defaultTime)

        then: "id is cleared, email is updated, and existing role and createdAt are kept"
        verifyAll(result) {
            id() == null
            email() == "raw@example.com"
            role() == UserRole.REGION_DIRECTOR
            createdAt() == existingTime
        }
    }

    def "withCreationDetails uses default role and default createdAt when missing"() {
        given: "a user with null role and null createdAt"
        OffsetDateTime defaultTime = OffsetDateTime.of(2026, 2, 10, 8, 0, 0, 0, ZoneOffset.UTC)
        User user = new User(UUID.randomUUID(), "Raw@Example.com", null, null)

        when: "applying creation details"
        User result = user.withCreationDetails("raw@example.com", UserRole.STANDARD_USER, defaultTime)

        then: "id is cleared and defaults are applied"
        verifyAll(result) {
            id() == null
            email() == "raw@example.com"
            role() == UserRole.STANDARD_USER
            createdAt() == defaultTime
        }
    }

    def "forCreation builds an unsaved user with email, role, and createdAt"() {
        given: "a creation timestamp"
        OffsetDateTime created = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC)

        when: "calling User.forCreation"
        User user = User.forCreation("new@example.com", UserRole.STANDARD_USER, created)

        then: "id is null and user details are populated"
        verifyAll(user) {
            id() == null
            email() == "new@example.com"
            role() == UserRole.STANDARD_USER
            createdAt() == created
        }
    }

    def "forEmailUpdate builds a user update with id and email only"() {
        given: "a user id"
        UUID userId = UUID.randomUUID()

        when: "calling User.forEmailUpdate"
        User user = User.forEmailUpdate(userId, "updated@example.com")

        then: "only id and email are set"
        verifyAll(user) {
            id() == userId
            email() == "updated@example.com"
            role() == null
            createdAt() == null
        }
    }

    def "withId returns a copy with the new id and keeps other fields"() {
        given: "an unsaved user and a new id"
        OffsetDateTime created = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        User user = User.forCreation("new@example.com", UserRole.STANDARD_USER, created)
        UUID newId = UUID.randomUUID()

        when: "calling withId"
        User updated = user.withId(newId)

        then: "the id is updated while other fields stay unchanged"
        verifyAll(updated) {
            id() == newId
            email() == "new@example.com"
            role() == UserRole.STANDARD_USER
            createdAt() == created
        }
    }

    def "withEmail returns a copy with the new email and keeps other fields"() {
        given: "an existing user"
        UUID userId = UUID.randomUUID()
        OffsetDateTime created = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        User user = new User(userId, "new@example.com", UserRole.STANDARD_USER, created)

        when: "calling withEmail"
        User updated = user.withEmail("canonical@example.com")

        then: "the email is updated while other fields stay unchanged"
        verifyAll(updated) {
            id() == userId
            email() == "canonical@example.com"
            role() == UserRole.STANDARD_USER
            createdAt() == created
        }
    }

    def "withRole returns a copy with the new role and keeps other fields"() {
        given: "an existing user"
        UUID userId = UUID.randomUUID()
        OffsetDateTime created = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        User user = new User(userId, "canonical@example.com", UserRole.STANDARD_USER, created)

        when: "calling withRole"
        User updated = user.withRole(UserRole.GLOBAL_ADMIN)

        then: "the role is updated while other fields stay unchanged"
        verifyAll(updated) {
            id() == userId
            email() == "canonical@example.com"
            role() == UserRole.GLOBAL_ADMIN
            createdAt() == created
        }
    }
}
