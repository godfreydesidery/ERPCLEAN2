package com.erp.platform.common.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thrown when an entity addressed by uid does not exist. Mapped to HTTP 404 by
 * {@link GlobalExceptionHandler}. The message is user-safe: it names the entity type only,
 * never the uid or internal identifier (hygiene rule — uids go to logs, not responses).
 */
public class NotFoundException extends RuntimeException {

    private static final Logger log = LoggerFactory.getLogger(NotFoundException.class);

    public NotFoundException(String message) {
        super(message);
    }

    /**
     * Builds a user-safe 404 exception for {@code entity}. The {@code uid} is NOT included in the
     * exception message (hygiene rule) but IS recorded at DEBUG level so the dropped identifier
     * remains traceable in logs.
     *
     * @param entity human-readable entity type name, e.g. {@code "Company"}
     * @param uid    the uid that was looked up (logged only, never surfaced to the caller)
     */
    public static NotFoundException of(String entity, String uid) {
        log.debug("404 {} uid={}", entity, uid);
        return new NotFoundException(entity + " not found.");
    }
}
