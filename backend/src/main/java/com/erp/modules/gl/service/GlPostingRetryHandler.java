package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.GlPostingRetry;
import com.erp.modules.gl.domain.enums.GlPostingFailureKind;

/**
 * Re-invokes the automatic poster behind a recorded GL posting failure (ACC-02). Implemented by the
 * module that owns the poster, so the GL module never depends on Stock (or any other module) to
 * re-post: it only asks every handler which kinds it supports.
 *
 * <p>Implementations must NOT be transactional themselves: the posters run in their own
 * REQUIRES_NEW transaction, and a failure escaping through a participating proxy would mark the
 * re-post's transaction rollback-only.
 */
public interface GlPostingRetryHandler {

    /** Whether this handler owns the poster of {@code kind}. */
    boolean supports(GlPostingFailureKind kind);

    /**
     * Re-invokes the poster with the recorded arguments and {@link GlPostingRetry#postingDate()}.
     *
     * @return the uid of the journal entry now posted, or {@code null} when the poster swallowed a
     *         failure again (its reason is captured by the caller)
     */
    String repost(GlPostingRetry retry);
}
