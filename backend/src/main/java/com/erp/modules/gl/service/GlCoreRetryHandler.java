package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.GlPostingRetry;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.GlPostingFailureKind;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.platform.common.api.ConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import com.erp.modules.sales.domain.dto.InvoicePostingTenderDto;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Re-posts the GL module's own automatic postings whose first attempt failed (ACC-02) by
 * re-invoking the same {@link GLPostingSafeInvoker} method with the recorded arguments. Not
 * transactional on purpose (see {@link GlPostingRetryHandler}).
 */
@Component
public class GlCoreRetryHandler implements GlPostingRetryHandler {

    private static final Set<GlPostingFailureKind> KINDS = EnumSet.of(
            GlPostingFailureKind.SALE,
            GlPostingFailureKind.JOURNAL_DRAFT,
            GlPostingFailureKind.REVERSAL,
            GlPostingFailureKind.SALE_VOID);

    private final GLPostingSafeInvoker invoker;
    private final JournalEntryRepository entries;
    private final ObjectMapper mapper;

    public GlCoreRetryHandler(GLPostingSafeInvoker invoker, JournalEntryRepository entries,
                              ObjectMapper mapper) {
        this.invoker = invoker;
        this.entries = entries;
        this.mapper  = mapper;
    }

    @Override
    public boolean supports(GlPostingFailureKind kind) {
        return KINDS.contains(kind);
    }

    @Override
    public String repost(GlPostingRetry r) {
        JsonNode a = r.args();
        JournalEntryDto posted = switch (r.kind()) {
            // Always the tender-split poster — the one a live sale uses — so a re-post produces the
            // same journal shape (one debit per tender account, residual to AR/CASH). A failure
            // recorded without tenders posts exactly like the single-debit form.
            case SALE -> invoker.postSaleWithTendersInNewTx(r.companyId(), r.branchId(),
                    r.sourceRef(), text(a, "currency"), decimal(a, "gross"), decimal(a, "net"),
                    decimal(a, "vat"), a.path("cashSale").asBoolean(false), tenders(a),
                    r.postingDate(), longValue(a, "costCentreValueId"),
                    longValue(a, "departmentValueId"), longValue(a, "projectId"),
                    longValue(a, "projectTaskId"));
            case JOURNAL_DRAFT -> {
                JournalEntryDraft d = mapper.convertValue(a.path("draft"), JournalEntryDraft.class);
                yield invoker.postInNewTx(new JournalEntryDraft(d.companyId(), d.branchId(),
                        r.postingDate(), d.description(), d.sourceType(), d.sourceRef(),
                        d.reversalOfId(), d.postedBy(), d.lines()));
            }
            case REVERSAL -> invoker.postReversalInNewTx(text(a, "originalEntryUid"),
                    r.postingDate(), r.sourceType(), r.sourceRef(), longValue(a, "postedBy"));
            case SALE_VOID -> {
                JournalEntry sale = entries.findByCompanyIdAndSourceTypeAndSourceRef(
                                r.companyId(), JournalSourceType.SALES, r.sourceRef())
                        .orElseThrow(() -> new ConflictException(
                                "The sale itself is not in the ledger yet. Re-post the sale's "
                                        + "own posting exception first, then this one."));
                yield invoker.postReversalInNewTx(sale.getUid(), r.postingDate(),
                        JournalSourceType.SALES_REVERSAL, r.sourceRef(), null);
            }
            default -> throw new IllegalArgumentException(
                    "This posting cannot be re-posted from the general ledger.");
        };
        return posted != null ? posted.uid() : null;
    }

    private List<InvoicePostingTenderDto> tenders(JsonNode args) {
        JsonNode t = args.path("tenders");
        if (!t.isArray()) {
            return List.of();
        }
        return mapper.convertValue(t, mapper.getTypeFactory()
                .constructCollectionType(List.class, InvoicePostingTenderDto.class));
    }

    private static String text(JsonNode args, String key) {
        JsonNode n = args.path(key);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static Long longValue(JsonNode args, String key) {
        String s = text(args, key);
        return s == null || s.isBlank() ? null : Long.valueOf(s);
    }

    private static BigDecimal decimal(JsonNode args, String key) {
        JsonNode n = args.path(key);
        if (n.isNumber()) {
            return n.decimalValue();
        }
        String s = text(args, key);
        return s == null || s.isBlank() ? null : new BigDecimal(s);
    }
}
