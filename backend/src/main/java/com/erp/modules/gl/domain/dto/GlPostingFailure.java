package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.GlPostingFailureKind;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an automatic poster knew when its GL posting failed (ACC-02) — enough to list the failure
 * and to re-invoke the same poster later. Built at the swallow point and handed to
 * {@code GlPostingFailureRecorder}; never persisted as-is (it becomes the detail of an
 * append-only audit row).
 *
 * @param kind           the poster that failed (decides how a re-post re-invokes it)
 * @param companyId      owning company
 * @param branchId       branch of the source document (nullable)
 * @param sourceType     journal source type the posting would have carried
 * @param sourceRef      source document uid the posting would have carried (nullable)
 * @param documentNumber human-readable document number (nullable; falls back to sourceRef)
 * @param postingDate    the posting date that was attempted
 * @param amount         the posting's total (debit side) when known, for the list screen
 * @param args           the poster's remaining arguments, JSON-serialisable
 */
public record GlPostingFailure(
        GlPostingFailureKind kind,
        Long companyId,
        Long branchId,
        JournalSourceType sourceType,
        String sourceRef,
        String documentNumber,
        LocalDate postingDate,
        BigDecimal amount,
        Map<String, Object> args
) {

    public GlPostingFailure {
        args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    /** Starts a failure with no extra arguments. */
    public static GlPostingFailure of(GlPostingFailureKind kind, Long companyId, Long branchId,
                                      JournalSourceType sourceType, String sourceRef,
                                      String documentNumber, LocalDate postingDate) {
        return new GlPostingFailure(kind, companyId, branchId, sourceType, sourceRef,
                documentNumber, postingDate, null, Map.of());
    }

    /** A copy carrying one more poster argument (null values are kept as JSON null). */
    public GlPostingFailure arg(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(args);
        next.put(key, value);
        return new GlPostingFailure(kind, companyId, branchId, sourceType, sourceRef,
                documentNumber, postingDate, amount, next);
    }

    /** A copy carrying the posting total shown on the exceptions screen. */
    public GlPostingFailure amount(BigDecimal total) {
        return new GlPostingFailure(kind, companyId, branchId, sourceType, sourceRef,
                documentNumber, postingDate, total, args);
    }
}
