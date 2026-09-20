package lol.pbu.kaiju.controller;

import io.micronaut.data.repository.CrudRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;

public interface ExistenceValidator {
    default <T, ID> void checkExists(CrudRepository<T, ID> repo, ID id) {
        if (!repo.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
    }
}
