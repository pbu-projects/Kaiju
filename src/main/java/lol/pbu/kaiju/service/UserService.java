package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.UserRole;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public interface UserService {

    @NonNull
    CursoredPage<User> getUsers(@NonNull CursoredPageable pageable);

    @NonNull
    User getCurrentUser(@NonNull UUID actorUserId);

    @NonNull
    Optional<User> getUserById(@NonNull UUID id, @NonNull UUID actorUserId);

    @NonNull
    Optional<User> getUserByEmail(@Nullable String email);

    @NonNull
    User createUser(@NonNull User user, @NonNull UUID actorUserId);

    @NonNull
    User updateUser(@NonNull UUID id, @NonNull User user, @NonNull UUID actorUserId);

    @NonNull
    User updateRole(@NonNull UUID id, @NonNull UserRole role, @NonNull UUID actorUserId);

    void deleteUser(@NonNull UUID id, @NonNull UUID actorUserId);

    @NonNull
    User provisionOrGetUser(@NonNull String email);

    @NonNull
    User provisionOrGetUser(@NonNull String email, @NonNull UserRole initialRole);
}
