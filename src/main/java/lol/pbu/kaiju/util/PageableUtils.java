package lol.pbu.kaiju.util;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Sort;

public final class PageableUtils {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private PageableUtils() {
    }

    public static CursoredPageable resolvePageable(@Nullable CursoredPageable pageable, String sortField) {
        if (pageable == null || pageable.isUnpaged()) {
            return CursoredPageable.from(DEFAULT_PAGE_SIZE, Sort.of(Sort.Order.asc(sortField)));
        }
        if (pageable.getSize() > MAX_PAGE_SIZE) {
            return CursoredPageable.from(
                    pageable.getNumber(),
                    pageable.cursor().orElse(null),
                    pageable.getMode(),
                    MAX_PAGE_SIZE,
                    pageable.getSort(),
                    pageable.requestTotal()
            );
        }
        return pageable;
    }
}
