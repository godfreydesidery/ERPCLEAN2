package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.enums.BillComparisonState;
import com.erp.modules.ap.repository.BillMatchRepository;
import com.erp.modules.ap.repository.BillMatchRepository.BillComparisonCounts;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Answers, for a bill that has already been read, how much of it was actually checked against a
 * purchase order and a goods receipt (UAT 2026-08-12).
 *
 * <p><b>Why this exists.</b> The live UAT that produced the fail-closed match fix also showed a
 * residual hole the fix cannot close: the 3-way control only binds on links the accountant chose to
 * declare, so a bill entered with no purchase-order reference at all still comes out MATCHED and
 * auto-posts. Refusing such bills is not an option — rent, utilities and professional fees have no
 * purchase order and blocking them would break real business, and whether PO-backed purchasing is
 * mandatory is the owner's policy call, not this class's. What is available without deciding policy
 * is visibility: make it impossible for a bill that posted without a comparison to hide among the
 * bills somebody checked.
 *
 * <p><b>Read-only, by construction.</b> This derives a state; it never decides whether a bill
 * matches, holds, posts or pays. Nothing here writes.
 *
 * <p><b>Silence is not evidence.</b> A bill with no {@code bill_match} rows at all answers
 * {@link BillComparisonState#NEVER_MATCHED}, never "compared". That case is real and reachable: AP
 * opening balances are stamped MATCHED and post their own journal entry without the match engine
 * ever running, so a signal that treated a missing row as a pass would quietly certify exactly the
 * payables nobody ever checked.
 */
@Component
public class BillComparisonReader {

    private final BillMatchRepository matches;

    public BillComparisonReader(BillMatchRepository matches) {
        this.matches = matches;
    }

    /**
     * The comparison state of ONE bill.
     *
     * @param billId    the bill's internal id; null (an unsaved bill) answers NEVER_MATCHED
     * @param lineCount how many lines the bill has — the caller has them loaded already, and a
     *                  comparison count only means something measured against the lines it should
     *                  cover
     */
    public BillComparisonState stateFor(Long billId, int lineCount) {
        if (billId == null) {
            return BillComparisonState.NEVER_MATCHED;
        }
        return snapshotFor(List.of(billId)).stateFor(billId, lineCount);
    }

    /**
     * The comparison state of a whole page of bills, in ONE query.
     *
     * <p>Per-bill resolution would be a query per row on a screen whose whole point is to be
     * scanned. Nulls and duplicates in {@code billIds} are fine and cost nothing.
     */
    public Snapshot snapshotFor(Collection<Long> billIds) {
        if (billIds == null || billIds.isEmpty()) {
            return new Snapshot(Map.of());
        }
        List<Long> ids = billIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return new Snapshot(Map.of());
        }
        Map<Long, Counts> counts = new HashMap<>();
        for (BillComparisonCounts row : matches.countComparedLinesByBillIds(ids)) {
            if (row.getBillId() == null) {
                continue;
            }
            counts.put(row.getBillId(),
                    new Counts(nullSafe(row.getMatchCount()), nullSafe(row.getComparedCount())));
        }
        return new Snapshot(counts);
    }

    private static long nullSafe(Long value) {
        return value != null ? value : 0L;
    }

    private record Counts(long matchCount, long comparedCount) {}

    /**
     * The comparison state of one page of bills, resolved up front by
     * {@link #snapshotFor(Collection)}.
     *
     * <p>A bill the snapshot has never heard of answers {@link BillComparisonState#NEVER_MATCHED}.
     * That default is the safe one and is the reason this is a type rather than a raw map: the
     * absent case must read as "nobody checked", never as "fine".
     */
    public static final class Snapshot {

        private final Map<Long, Counts> counts;

        private Snapshot(Map<Long, Counts> counts) {
            this.counts = counts;
        }

        /**
         * @param billId    the bill; unknown or null answers NEVER_MATCHED
         * @param lineCount the bill's line count, from the lines the caller already loaded
         */
        public BillComparisonState stateFor(Long billId, int lineCount) {
            Counts c = billId != null ? counts.get(billId) : null;
            if (c == null || c.matchCount() == 0) {
                return BillComparisonState.NEVER_MATCHED;
            }
            if (lineCount <= 0) {
                // A bill with no lines has nothing that could have been compared. Refusing to call
                // that "fully compared" costs nothing and stops a degenerate row reading as clean.
                return BillComparisonState.NO_LINES_COMPARED;
            }
            if (c.comparedCount() >= lineCount) {
                return BillComparisonState.ALL_LINES_COMPARED;
            }
            return c.comparedCount() == 0
                    ? BillComparisonState.NO_LINES_COMPARED
                    : BillComparisonState.SOME_LINES_COMPARED;
        }
    }
}
