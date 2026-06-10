package com.erp.modules.tax.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.tax.domain.entity.VatReturn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Posts the synchronous VAT settlement journal on filing (ADR-0017 D-8).
 *
 * <p>Settlement legs (D-8 exact shape):
 * <pre>
 *   DR  VAT_PAYABLE (2200)       output_vat         — clear period output
 *   CR  VAT_INPUT   (1400)       input_vat           — clear period input
 *   CR/DR VAT_DUE   (2300)       |output - input|    — net payable (CR) or credit (DR)
 * </pre>
 * Zero-valued legs are omitted (chk_journal_line_one_side constraint).
 * Adjustments and opening_credit affect the return's net figure (the operator reconciles/pays),
 * but the settlement entry clears the two control accounts O and I to VAT_DUE (v1 default — D-8 note).
 * The entry is balanced: Σdebit == Σcredit enforced by GLPostingService.
 */
@Service
public class VatReturnFilingPoster {

    private final GLPostingService  glPosting;
    private final GLConfigResolver  glConfig;

    public VatReturnFilingPoster(GLPostingService glPosting, GLConfigResolver glConfig) {
        this.glPosting = glPosting;
        this.glConfig  = glConfig;
    }

    /**
     * Post the settlement journal for the given filed return.
     * Must be called inside the file TX (same transaction — NFR-VAT-04).
     *
     * @param vatReturn the return being filed (totals already frozen by the caller)
     * @param actorId   the filing user's id
     * @return the posted journal entry UID
     */
    public String post(VatReturn vatReturn, Long actorId) {
        Long   companyId = vatReturn.getCompanyId();
        String currency  = "TZS"; // BR-VAT-13: base currency only

        ChartOfAccount vatPayableAcct = glConfig.resolve(companyId, GlConfigKey.VAT_PAYABLE);
        ChartOfAccount vatInputAcct   = glConfig.resolve(companyId, GlConfigKey.VAT_INPUT);
        ChartOfAccount vatDueAcct     = glConfig.resolve(companyId, GlConfigKey.VAT_DUE);

        BigDecimal O = vatReturn.getOutputVat();   // the period output (clears 2200)
        BigDecimal I = vatReturn.getInputVat();    // the period input  (clears 1400)

        List<LineDraft> lines = new ArrayList<>();

        // Leg 1: DR VAT_PAYABLE output (clear output off 2200)
        if (O.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(vatPayableAcct.getId(),
                    O, BigDecimal.ZERO, currency,
                    "VAT settlement — clear output " + vatReturn.getReturnNumber()));
        }

        // Leg 2: CR VAT_INPUT input (clear input off 1400)
        if (I.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(vatInputAcct.getId(),
                    BigDecimal.ZERO, I, currency,
                    "VAT settlement — clear input " + vatReturn.getReturnNumber()));
        }

        // Leg 3: VAT_DUE — the balancing leg = O - I
        // If O > I: CR VAT_DUE (net payable to TRA)
        // If I > O: DR VAT_DUE (net credit, a debit balance on 2300)
        // If O == I: no leg needed (zero)
        BigDecimal netOI = O.subtract(I);
        if (netOI.compareTo(BigDecimal.ZERO) > 0) {
            // net payable: CR VAT_DUE
            lines.add(new LineDraft(vatDueAcct.getId(),
                    BigDecimal.ZERO, netOI, currency,
                    "VAT due — net payable " + vatReturn.getReturnNumber()));
        } else if (netOI.compareTo(BigDecimal.ZERO) < 0) {
            // net credit: DR VAT_DUE
            lines.add(new LineDraft(vatDueAcct.getId(),
                    netOI.negate(), BigDecimal.ZERO, currency,
                    "VAT due — net credit " + vatReturn.getReturnNumber()));
        }

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
                "VAT Return Filing " + vatReturn.getReturnNumber(),
                JournalSourceType.VAT_RETURN,
                vatReturn.getUid(),
                null,
                actorId,
                lines);

        JournalEntryDto posted = glPosting.post(draft);
        return posted.uid();
    }
}
