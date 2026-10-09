package lol.pbu.kaiju.util;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Sort;
import io.micronaut.data.repository.CrudRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;

public interface ControllerUtils {

    int DEFAULT_PAGE_SIZE = 20;

    default <T, K> void checkExists(CrudRepository<T, K> repo, K id) {
        if (!repo.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
    }

    default CursoredPageable resolvePageable(@Nullable CursoredPageable pageable, String sortField) {
        return (pageable == null || pageable.isUnpaged())
                ? CursoredPageable.from(DEFAULT_PAGE_SIZE, Sort.of(Sort.Order.asc(sortField)))
                : pageable;
    }
}
