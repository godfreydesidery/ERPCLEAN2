package com.erp.platform.common.api;

import org.springframework.data.domain.Page;

/**
 * Reusable paging metadata returned in {@link ApiResponse#meta} for every paged endpoint
 * (ADR-0004 D-7). Placed in {@code platform.common.api} so any module's controller can use it
 * without a cross-module import.
 *
 * <p>{@code totalElements} is an {@code int} COUNT (not a Long id), so it serialises as a JSON
 * number — the global Long-as-string config protects 64-bit ids, and PageMeta carries none. An int
 * is ample for page counts. The web reads it as a number.
 *
 * @param page          zero-based page number (matches {@code Pageable.getPageNumber()})
 * @param size          page size requested
 * @param totalElements total matching records across all pages
 * @param totalPages    total number of pages (ceil(totalElements / size))
 * @param hasNext       {@code true} when more pages follow the current one
 */
public record PageMeta(int page, int size, int totalElements, int totalPages, boolean hasNext) {

    /**
     * Creates a {@code PageMeta} from a Spring Data {@link Page}. Works with any content type.
     *
     * @param p the page returned by a repository or service
     * @return metadata describing this page
     */
    public static PageMeta from(Page<?> p) {
        return new PageMeta(
                p.getNumber(),
                p.getSize(),
                (int) p.getTotalElements(),
                p.getTotalPages(),
                p.hasNext());
    }
}
