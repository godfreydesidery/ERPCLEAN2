package com.erp.modules.tax.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.tax.domain.entity.VatAdjustment;
import com.erp.modules.tax.domain.entity.VatReturn;
import com.erp.modules.tax.domain.enums.VatAdjustmentSign;
import com.erp.modules.tax.repository.VatAdjustmentRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Posts the synchronous VAT settlement journal on filing (ADR-0017 D-8).
 *
 * <p>Settlement legs (signed amounts; a negative figure flips the leg's side):
 * <pre>
 *   DR  VAT_PAYABLE (2200)   O            — clear period output (CR when O &lt; 0)
 *   CR  VAT_INPUT   (1400)   I            — clear period input  (DR when I &lt; 0)
 *   DR/CR  adjustment counter  a          — one leg per adjustment (ACC-06, below)
 *   CR/DR VAT_DUE   (2300)   O − I + Σa   — the filed net before brought-forward credit
 * </pre>
 * O and I come from the computation reader and already net credit notes, debit notes and voids
 * (ACC-06/24), so legs 1–2 leave 2200 and 1400 at zero for the period. Each adjustment moves VAT
 * Due by its signed amount and posts its counter leg where the underlying VAT sits:
 * <ul>
 *   <li>CREDIT_NOTE_VAT → VAT_PAYABLE (an output-side correction, e.g. a back-dated credit note
 *       that debited 2200 in an already-filed month);</li>
 *   <li>DEBIT_NOTE_VAT → VAT_INPUT (an input-side correction);</li>
 *   <li>BAD_DEBT_RELIEF → BAD_DEBT_EXPENSE (the relief recovers the VAT part of a written-off
 *       debt);</li>
 *   <li>PRIOR_PERIOD_CORRECTION / OTHER → VAT_PAYABLE when it INCREASES the net (under-declared
 *       output), VAT_INPUT when it DECREASES it (under-claimed input).</li>
 * </ul>
 * So 2300 after filing carries exactly net VAT (the opening credit is already the debit balance
 * the prior filing left on 2300). Zero-valued legs are omitted (chk_journal_line_one_side).
 * The entry is balanced: Σdebit == Σcredit enforced by GLPostingService.
 */
@Service
public class VatReturnFilingPoster {

    private final GLPostingService        glPosting;
    private final GLConfigResolver        glConfig;
    private final VatAdjustmentRepository adjustments;

    public VatReturnFilingPoster(GLPostingService glPosting, GLConfigResolver glConfig,
                                 VatAdjustmentRepository adjustments) {
        this.glPosting   = glPosting;
        this.glConfig    = glConfig;
        this.adjustments = adjustments;
    }

    /**
     * Post the settlement journal for the given filed return.
     * Must be called inside the file TX (same transaction — NFR-VAT-04).
     *
     * @param vatReturn the return being filed (totals already frozen by the caller)
     * @param actorId   the filing user's id
     * @return the posted journal entry UID, or null for a nil return (nothing to settle)
     */
    public String post(VatReturn vatReturn, Long actorId) {
        Long   companyId = vatReturn.getCompanyId();
        String currency  = "TZS"; // BR-VAT-13: base currency only
        String ref       = vatReturn.getReturnNumber();

        ChartOfAccount vatPayableAcct = glConfig.resolve(companyId, GlConfigKey.VAT_PAYABLE);
        ChartOfAccount vatInputAcct   = glConfig.resolve(companyId, GlConfigKey.VAT_INPUT);
        ChartOfAccount vatDueAcct     = glConfig.resolve(companyId, GlConfigKey.VAT_DUE);

        BigDecimal O = vatReturn.getOutputVat();   // the period output (clears 2200)
        BigDecimal I = vatReturn.getInputVat();    // the period input  (clears 1400)

        List<LineDraft> lines = new ArrayList<>();

        // Leg 1: clear output off 2200 — DR when positive, CR when credits exceeded sales.
        signedLeg(lines, vatPayableAcct.getId(), O, currency, "VAT settlement — clear output " + ref);
        // Leg 2: clear input off 1400 — CR when positive, DR when debit notes exceeded bills.
        signedLeg(lines, vatInputAcct.getId(), I.negate(), currency,
                "VAT settlement — clear input " + ref);

        // Leg(s) 3: adjustments (ACC-06) — each to the account its VAT actually sits on.
        BigDecimal adjTotal = BigDecimal.ZERO;
        for (VatAdjustment a : adjustments.findByVatReturnId(vatReturn.getId())) {
            BigDecimal signed = a.signedAmount();
            if (signed.signum() == 0) {
                continue;
            }
            adjTotal = adjTotal.add(signed);
            Long counter = glConfig.resolve(companyId, counterKey(a)).getId();
            signedLeg(lines, counter, signed, currency,
                    "VAT adjustment " + a.getReason().name().replace('_', ' ').toLowerCase()
                            + " — " + ref);
        }

        // Leg 4: VAT_DUE — the balancing leg = O − I + Σadjustments.
        // Positive: CR VAT_DUE (net payable to TRA); negative: DR VAT_DUE (a credit with TRA).
        BigDecimal due = O.subtract(I).add(adjTotal);
        signedLeg(lines, vatDueAcct.getId(), due.negate(), currency,
                (due.signum() >= 0 ? "VAT due — net payable " : "VAT due — net credit ") + ref);

        // Nil-activity period: no output and no input → nothing to settle, so post NO journal.
        // A GL entry needs >=2 non-zero legs (BR-GL-08 / chk_journal_line_one_side); a nil return
        // would only have zero legs. The return still files and locks (D-4) with posted_journal_uid
        // left null — a legal nil VAT return has no GL movement (chk_vat_return_filed_fields permits
        // a FILED return with no posted journal).
        if (lines.isEmpty()) {
            return null;
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId,
                null,           // company-level posting; no branch tag
                vatReturn.getFilingDate(),
                "VAT Return Filing " + ref,
                JournalSourceType.VAT_RETURN,
                vatReturn.getUid(),
                null,
                actorId,
                lines);

        JournalEntryDto posted = glPosting.post(draft);
        return posted.uid();
    }

    /** Where an adjustment's counter leg posts (see the class comment). */
    static GlConfigKey counterKey(VatAdjustment a) {
        return switch (a.getReason()) {
            case CREDIT_NOTE_VAT -> GlConfigKey.VAT_PAYABLE;
            case DEBIT_NOTE_VAT  -> GlConfigKey.VAT_INPUT;
            case BAD_DEBT_RELIEF -> GlConfigKey.BAD_DEBT_EXPENSE;
            case PRIOR_PERIOD_CORRECTION, OTHER -> a.getSign() == VatAdjustmentSign.INCREASE
                    ? GlConfigKey.VAT_PAYABLE : GlConfigKey.VAT_INPUT;
        };
    }

    /** A positive amount debits the account, a negative one credits it; zero adds no leg. */
    private static void signedLeg(List<LineDraft> lines, Long accountId, BigDecimal debitPositive,
                                  String currency, String memo) {
        int sign = debitPositive.signum();
        if (sign > 0) {
            lines.add(new LineDraft(accountId, debitPositive, BigDecimal.ZERO, currency, memo));
        } else if (sign < 0) {
            lines.add(new LineDraft(accountId, BigDecimal.ZERO, debitPositive.negate(), currency, memo));
        }
    }
}
