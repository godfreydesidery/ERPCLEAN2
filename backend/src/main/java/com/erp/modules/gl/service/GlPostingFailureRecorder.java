package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.GlPostingFailure;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditOpenItemsQuery;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.Ulid;
import com.erp.platform.common.money.FxRateNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a swallowed automatic GL posting failure durable (ACC-02).
 *
 * <p>The automatic posters ({@link GLPostingSafeInvoker}, {@code InventoryGlPoster}, the sale-void
 * handler) deliberately swallow GL failures so the business document — the sale, the stock
 * movement — still stands. Before this recorder the only trace was a WARN in the server log, so
 * revenue, VAT and COGS went missing from the books unnoticed. Every swallow point now hands the
 * failure here, and it becomes one {@code GL.POSTING.FAILED} row in the append-only audit trail:
 * target_type {@value #TARGET_TYPE}, target_uid a fresh ULID naming the exception, detail carrying
 * the source, the user-safe reason and the arguments needed to re-invoke the same poster. Closing
 * it later APPENDS a {@code GL.POSTING.RESOLVED} row — the failure row is never edited.
 *
 * <p><strong>Transactions.</strong> {@link #record} runs in its OWN transaction (REQUIRES_NEW): the
 * posting transaction that failed is rollback-only, or even aborted at the database, so nothing
 * written there would survive. The recorder never throws — a failure to record is logged at ERROR
 * and swallowed, exactly like the posting failure it describes, so it can never take the business
 * document down with it.
 *
 * <p><strong>Re-post capture.</strong> A re-post re-invokes the original poster, which swallows
 * again on failure and would record a second exception for the same source. While
 * {@link #capturing} is active on the current thread the recorder writes nothing and instead keeps
 * the reason, so the re-post can report why it still fails.
 */
@Component
public class GlPostingFailureRecorder {

    /** audit_logs.target_type of a GL posting exception. */
    public static final String TARGET_TYPE = "gl_posting_exceptions";

    private static final Logger log = LoggerFactory.getLogger(GlPostingFailureRecorder.class);
    private static final int MAX_REASON = 500;
    private static final ThreadLocal<String[]> CAPTURE = new ThreadLocal<>();

    private final AuditService audit;
    private final AuditOpenItemsQuery openItems;
    private final JournalEntryRepository entries;

    public GlPostingFailureRecorder(AuditService audit, AuditOpenItemsQuery openItems,
                                   JournalEntryRepository entries) {
        this.audit     = audit;
        this.openItems = openItems;
        this.entries   = entries;
    }

    /**
     * Records one swallowed posting failure. Never throws.
     *
     * @param failure what the poster knew
     * @param cause   the exception the posting raised
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(GlPostingFailure failure, Throwable cause) {
        String reason = userSafeReason(cause);
        if (captured(reason)) {
            return;
        }
        persist(failure, reason);
    }

    /**
     * Records a failed reversal of a known journal entry. The reversal call carries no company, so
     * it is read here, in this transaction, from the original entry — the posting transaction that
     * failed may be aborted at the database. The branch hint is used only when it comes from the
     * same company. Never throws.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordReversal(String originalEntryUid, Long contextCompanyId, Long branchHint,
                               GlPostingFailure failure, Throwable cause) {
        String reason = userSafeReason(cause);
        if (captured(reason)) {
            return;
        }
        try {
            Long companyId = entries.findCompanyIdByUid(originalEntryUid).orElse(null);
            if (companyId == null) {
                log.warn("GL reversal failure not recorded — original entry not found");
                return;
            }
            Long branchId = companyId.equals(contextCompanyId) ? branchHint : null;
            persist(new GlPostingFailure(failure.kind(), companyId, branchId, failure.sourceType(),
                    failure.sourceRef(), failure.documentNumber(), failure.postingDate(),
                    failure.amount(), failure.args()), reason);
        } catch (RuntimeException ex) {
            log.error("Could not record GL reversal failure: {}", ex.getMessage(), ex);
        }
    }

    private static boolean captured(String reason) {
        String[] capture = CAPTURE.get();
        if (capture != null) {
            capture[0] = reason;
            return true;
        }
        return false;
    }

    private void persist(GlPostingFailure failure, String reason) {
        try {
            String kind       = failure.kind().name();
            String sourceType = failure.sourceType() != null ? failure.sourceType().name() : null;
            if (failure.sourceRef() != null && sourceType != null
                    && openItems.existsOpenItem(failure.companyId(), TARGET_TYPE,
                            AuditActions.GL_POSTING_FAILED, AuditActions.GL_POSTING_RESOLVED,
                            kind, sourceType, failure.sourceRef())) {
                // Same source already listed and still open (e.g. a redelivered event). One row
                // per source keeps the list honest and the re-post single.
                log.info("GL posting exception for {} {} already open — not recorded twice",
                        sourceType, failure.sourceRef());
                return;
            }
            long journalsAtFailure = failure.sourceRef() != null && failure.sourceType() != null
                    ? entries.countByCompanyIdAndSourceTypeAndSourceRef(
                            failure.companyId(), failure.sourceType(), failure.sourceRef())
                    : 0L;

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("kind", kind);
            detail.put("sourceType", sourceType);
            detail.put("sourceRef", failure.sourceRef());
            detail.put("documentNumber", failure.documentNumber());
            detail.put("postingDate", failure.postingDate() != null
                    ? failure.postingDate().toString() : null);
            detail.put("amount", failure.amount() != null ? failure.amount().toPlainString() : null);
            detail.put("reason", reason);
            detail.put("journalsAtFailure", journalsAtFailure);
            detail.put("args", failure.args());

            audit.recordInScope(
                    AuditEvent.of(AuditActions.GL_POSTING_FAILED, TARGET_TYPE, null, Ulid.next())
                            .detail(detail),
                    failure.companyId(), failure.branchId());
        } catch (RuntimeException ex) {
            log.error("Could not record GL posting exception for company={} {} {}: {}",
                    failure.companyId(), failure.sourceType(), failure.sourceRef(),
                    ex.getMessage(), ex);
        }
    }

    /**
     * Outcome of a {@link #capturing} run.
     *
     * @param reason user-safe reason of the last failure the poster swallowed (null when none)
     * @param error  the exception that escaped the action, if any (e.g. the poster's own
     *               transaction reporting an unexpected rollback)
     */
    public record Capture(String reason, RuntimeException error) {}

    /**
     * Runs {@code action} with recording suspended on this thread and reports what the action's
     * poster swallowed. Never throws what the action throws — it is returned in the result.
     */
    public Capture capturing(Runnable action) {
        String[] previous = CAPTURE.get();
        String[] slot = new String[1];
        CAPTURE.set(slot);
        try {
            action.run();
            return new Capture(slot[0], null);
        } catch (RuntimeException ex) {
            return new Capture(slot[0], ex);
        } finally {
            if (previous == null) {
                CAPTURE.remove();
            } else {
                CAPTURE.set(previous);
            }
        }
    }

    /**
     * The text shown on the exceptions screen. The GL engine's refusals (closed period, inactive or
     * unmapped account, unbalanced, missing FX rate…) are written for users and pass through; any
     * other exception is an internal fault whose text must not reach a screen.
     */
    static String userSafeReason(Throwable cause) {
        Throwable t = cause;
        while (t != null) {
            if (t instanceof IllegalArgumentException
                    || t instanceof IllegalStateException
                    || t instanceof ConflictException
                    || t instanceof NotFoundException
                    || t instanceof FxRateNotFoundException) {
                String msg = t.getMessage();
                if (msg != null && !msg.isBlank()) {
                    return msg.length() > MAX_REASON ? msg.substring(0, MAX_REASON) + "…" : msg;
                }
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return "The posting failed for an unexpected reason. Support can find the details in the "
                + "server log.";
    }
}
