package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.UserRepository
import net.datafaker.Faker

import java.time.OffsetDateTime
import java.util.UUID

class UserBuilder {

    private final UserRepository userRepository
    private final Faker faker
    private final KaijuDataBuilder dataBuilder

    private UUID id
    private String email
    private UserRole role = UserRole.STANDARD_USER
    private OffsetDateTime createdAt

    UserBuilder(UserRepository userRepository, Faker faker, KaijuDataBuilder dataBuilder = null) {
        this.userRepository = userRepository
        this.faker = faker
        this.dataBuilder = dataBuilder
    }

    UserBuilder asAdmin() {
        this.role = UserRole.GLOBAL_ADMIN
        return this
    }

    UserBuilder asDirector() {
        this.role = UserRole.REGION_DIRECTOR
        return this
    }

    UserBuilder asAgent() {
        this.role = UserRole.REGION_AGENT
        return this
    }

    UserBuilder asVolunteer() {
        this.role = UserRole.STANDARD_USER
        return this
    }

    UserBuilder asStandardUser() {
        this.role = UserRole.STANDARD_USER
        return this
    }

    UserBuilder withRole(UserRole role) {
        this.role = role
        return this
    }

    UserBuilder withEmail(String email) {
        this.email = email
        return this
    }

    UserBuilder withId(UUID id) {
        this.id = id
        return this
    }

    UserBuilder withCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt
        return this
    }

    User build() {
        return new User(
                id,
                email ?: generateEmail(),
                role,
                createdAt ?: OffsetDateTime.now()
        )
    }

    User persist() {
        assert userRepository != null : "Cannot persist user without a UserRepository"
        User saved = userRepository.save(build())
        dataBuilder?.trackUser(saved.id())
        return saved
    }

    private String generateEmail() {
        boolean useStarWars = faker.random().nextBoolean()
        String rawName = useStarWars ? faker.starWars().character() : faker.zelda().character()
        String cleanName = rawName.toLowerCase()
                .replaceAll(/[^a-z0-9]/, '-')
                .replaceAll(/-+/, '-')
                .replaceAll(/^[-]+|[-]+$/, '')
        if (cleanName.isEmpty()) {
            cleanName = "hero"
        }
        return "${cleanName}-${UUID.randomUUID().toString().substring(0, 8)}@test.pbu.lol"
    }
}
