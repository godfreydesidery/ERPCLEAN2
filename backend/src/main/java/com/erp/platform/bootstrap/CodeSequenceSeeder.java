package com.erp.platform.bootstrap;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a company's document-number rows up front, instead of on first use (ADR-0062 P5-6).
 *
 * <h2>The race this removes</h2>
 *
 * Every number generator does
 * {@code findByCompanyIdAndEntityKindForUpdate(...).orElseGet(() -> save(new CodeSequence(...)))}.
 * The {@code PESSIMISTIC_WRITE} in that finder locks a row — and there is no row to lock the first
 * time. Two clerks raising the first document of a kind at the same moment therefore both find
 * nothing, both insert, and one gets a unique violation on {@code (company_id, entity_kind)}.
 *
 * <p>Under one database per install this happened once, years ago, to somebody who retried and never
 * mentioned it. It is worse now: every newly provisioned tenant re-arms all thirty kinds at once, on
 * exactly the busy first morning when two people are most likely to be entering the same kind of
 * document.
 *
 * <p>The loser's failure is benign — a misleading 409 and a clean rollback, never a duplicate
 * number — which is why this is hardening rather than a defect. Seeding removes it entirely: the row
 * exists, so the lock has something to take.
 *
 * <h2>Why the list is duplicated here, and how it stays honest</h2>
 *
 * The kinds live as private constants across twenty-two generator classes; there is no shared enum
 * to read. Rather than invent one and touch every generator, the list is repeated here and
 * {@code CodeSequenceSeederCoversAllKindsTest} re-derives the true set from the source at build time
 * and fails when the two diverge. A new generator therefore cannot silently opt out of seeding — the
 * build says so, naming the kind.
 */
@Service
public class CodeSequenceSeeder {

    private static final Logger log = LoggerFactory.getLogger(CodeSequenceSeeder.class);

    /**
     * Every {@code entity_kind} allocated through {@code CodeSequence}, as of V103.
     *
     * <p>Kept in sync by {@code CodeSequenceSeederCoversAllKindsTest} — do not edit by hand without
     * running it.
     */
    public static final List<String> KINDS = List.of(
            "APPROVAL", "AP_BILL", "AP_DEBIT_NOTE", "AP_PAYMENT", "AP_PAYMENT_RUN",
            "AR_CREDIT_NOTE", "AR_RECEIPT", "BANK_RECONCILIATION", "BUDGET",
            "CASH_BANK_ACCOUNT", "CASH_COUNT", "CASH_TRANSFER", "CASH_TXN",
            "DEPRECIATION_RUN", "DOCUMENT", "FIXED_ASSET", "HR_LOAN", "HR_PAYROLL_RUN",
            "HR_PAYSLIP", "JOURNAL_BATCH", "PETTY_CASH_FUND", "PETTY_CASH_TXN", "PRODUCT",
            "PROJECT", "PROJECT_ISSUE", "ROUTE", "SALES_INVOICE", "VAT_RETURN", "WHT",
            "WORK_ORDER");

    private final CodeSequenceRepository sequences;

    public CodeSequenceSeeder(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /**
     * Idempotent: skips any kind the company already has, so it is safe on a company that has been
     * trading for years as well as on one created a second ago.
     */
    @Transactional
    public void seedDefaults(Long companyId) {
        if (companyId == null) {
            return;
        }
        int created = 0;
        for (String kind : KINDS) {
            if (sequences.existsByCompanyIdAndEntityKind(companyId, kind)) {
                continue;
            }
            sequences.save(new CodeSequence(companyId, kind));
            created++;
        }
        if (created > 0) {
            log.info("Seeded {} document-number sequence(s) for company {}.", created, companyId);
        }
    }
}
