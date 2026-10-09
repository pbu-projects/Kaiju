package lol.pbu.kaiju.util;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Sort;

public final class PageableUtils {

    public static final int DEFAULT_PAGE_SIZE = 20;

    private PageableUtils() {
    }

    public static CursoredPageable resolvePageable(@Nullable CursoredPageable pageable, String sortField) {
        return (pageable == null || pageable.isUnpaged())
                ? CursoredPageable.from(DEFAULT_PAGE_SIZE, Sort.of(Sort.Order.asc(sortField)))
                : pageable;
    }
}
