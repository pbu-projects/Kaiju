package lol.pbu.kaiju.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

@Serdeable
@MappedEntity("users")
public record User(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "User email is required.")
        @Email(message = "User email must be a valid email address.")
        @Size(min = 1, max = 255, message = "User email must be between 1 and 255 characters.")
        String email,

        @NotNull(message = "User role is required.")
        UserRole role,
        OffsetDateTime createdAt
) {
    public static User forCreation(
            @NotNull String email,
            @NotNull UserRole role,
            @NotNull OffsetDateTime createdAt
    ) {
        return new User(null, email, role, createdAt);
    }

    public static User forEmailUpdate(@NotNull UUID id, @NotNull String email) {
        return new User(id, email, null, null);
    }

    /**
     * Instead of using setters, this method gives the opportunity to take an existing user ID and assign that to
     * the user properties in this user record.
     *
     * @param newId The UUID to assign to the user.
     * @return A new {@link User} with the given ID.
     */
    public User withId(@NotNull UUID newId) {
        return new User(
                newId,
                this.email(),
                this.role(),
                this.createdAt()
        );
    }

    public User withCreationDetails(
            @Nullable String canonicalEmail,
            @NotNull UserRole defaultRole,
            @NotNull OffsetDateTime defaultCreatedAt
    ) {
        return new User(
                null,
                canonicalEmail,
                this.role() != null ? this.role() : defaultRole,
                this.createdAt() != null ? this.createdAt() : defaultCreatedAt
        );
    }

    public User withEmail(@Nullable String canonicalEmail) {
        return new User(
                this.id(),
                canonicalEmail,
                this.role(),
                this.createdAt()
        );
    }

    public User withRole(@NotNull UserRole newRole) {
        return new User(
                this.id(),
                this.email(),
                newRole,
                this.createdAt()
        );
    }
}
