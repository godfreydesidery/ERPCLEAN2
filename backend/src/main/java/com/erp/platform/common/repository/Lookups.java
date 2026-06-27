package com.erp.platform.common.repository;

import com.erp.platform.common.api.NotFoundException;
import java.util.Optional;

/**
 * Small DRY helper for the "resolve by uid or 404" pattern that every service repeats. Keeps the
 * lookup-or-throw logic in one place instead of copy-pasting {@code orElseThrow} across services.
 */
public final class Lookups {

    private Lookups() {
    }

    /**
     * Returns the value, or throws a {@link NotFoundException} naming the entity type.
     *
     * @param found  the optional from a {@code findByUid} call
     * @param entity human-readable entity name for the error message (e.g. "Company")
     * @param uid    the uid that was not found (used for logging by the caller; not in the message)
     */
    public static <T> T orNotFound(Optional<T> found, String entity, String uid) {
        return found.orElseThrow(() -> NotFoundException.of(entity, uid));
    }
}
