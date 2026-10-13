package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.GlPostingExceptionDto;
import com.erp.modules.gl.domain.dto.GlPostingRepostResultDto;
import com.erp.modules.gl.domain.dto.GlSalesTieOutDto;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The GL "Posting exceptions" list and its re-post action (ACC-02). Exceptions are recorded by
 * {@link GlPostingFailureRecorder} at every point where an automatic GL posting is swallowed.
 */
public interface GlPostingExceptionService {

    /**
     * Exceptions of one company, newest first.
     *
     * @param sourceType      journal source type filter (blank = all)
     * @param from            earliest attempted posting date (null = open-ended)
     * @param to              latest attempted posting date (null = open-ended)
     * @param includeResolved false lists only the open exceptions
     */
    Page<GlPostingExceptionDto> list(Long companyId, String sourceType, LocalDate from,
                                     LocalDate to, boolean includeResolved, Pageable pageable);

    /**
     * Re-invokes the poster behind one open exception, exactly once. Refused when the exception is
     * already resolved; closed WITHOUT posting when a journal for the same source has appeared
     * since the failure. On success appends a {@code GL.POSTING.RESOLVED} audit row.
     *
     * @param postingDate post on this date instead of the original one (null keeps it)
     */
    GlPostingRepostResultDto repost(Long companyId, String exceptionUid, LocalDate postingDate);

    /**
     * Sales-vs-GL revenue/VAT tie-out for a date range (null dates = the current month).
     */
    GlSalesTieOutDto salesTieOut(Long companyId, LocalDate from, LocalDate to);
}
