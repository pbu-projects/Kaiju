package lol.pbu.kaiju.util;

import io.micronaut.data.repository.CrudRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;

public interface ControllerUtils {
    default <T, K> void checkExists(CrudRepository<T, K> repo, K id) {
        if (!repo.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
    }
}
