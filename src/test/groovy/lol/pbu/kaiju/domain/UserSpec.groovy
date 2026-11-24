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

    def "factory and copy methods update expected user fields"() {
        given: "user details"
        UUID id1 = UUID.randomUUID()
        UUID id2 = UUID.randomUUID()
        OffsetDateTime created = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC)

        when: "calling User helper methods"
        User forCreate = User.forCreation("new@example.com", UserRole.STANDARD_USER, created)
        User forEmail = User.forEmailUpdate(id1, "updated@example.com")
        User withIdUser = forCreate.withId(id2)
        User withEmailUser = withIdUser.withEmail("canonical@example.com")
        User withRoleUser = withEmailUser.withRole(UserRole.GLOBAL_ADMIN)

        then: "all fields match expected values"
        verifyAll {
            forCreate.id() == null
            forCreate.email() == "new@example.com"
            forCreate.role() == UserRole.STANDARD_USER
            forCreate.createdAt() == created
            forEmail.id() == id1
            forEmail.email() == "updated@example.com"
            forEmail.role() == null
            forEmail.createdAt() == null
            withIdUser.id() == id2
            withEmailUser.email() == "canonical@example.com"
            withRoleUser.role() == UserRole.GLOBAL_ADMIN
        }
    }
}
