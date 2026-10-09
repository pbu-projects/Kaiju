package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.UserRole;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface UserService {

    @NonNull
    CursoredPage<User> getUsers(@NonNull CursoredPageable pageable);

    @NonNull
    Optional<User> getUserById(@NonNull UUID id);

    @NonNull
    Optional<User> getUserByEmail(@NonNull String email);

    @NonNull
    User createUser(@NonNull User user);

    @NonNull
    User updateUser(@NonNull UUID id, @NonNull User user);

    void updateRole(@NonNull UUID id, @NonNull UserRole role);

    void deleteUser(@NonNull UUID id);

    @NonNull
    User provisionOrGetUser(@NonNull String email);
}
