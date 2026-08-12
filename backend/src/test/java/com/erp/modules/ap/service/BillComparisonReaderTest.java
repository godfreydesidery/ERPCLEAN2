package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.enums.BillComparisonState;
import com.erp.modules.ap.repository.BillMatchRepository;
import com.erp.modules.ap.repository.BillMatchRepository.BillComparisonCounts;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one property that matters here: <b>silence must never read as verification.</b>
 *
 * <p>A bill can carry a payable, read MATCHED and post to the ledger with no {@code bill_match} row
 * behind it at all — an AP opening balance does exactly that. If the absent case defaulted to
 * "compared", this signal would certify precisely the payables nobody ever checked, which is worse
 * than having no signal.
 */
class BillComparisonReaderTest {

    private BillMatchRepository  matches;
    private BillComparisonReader reader;

    @BeforeEach
    void setUp() {
        matches = mock(BillMatchRepository.class);
        reader  = new BillComparisonReader(matches);
    }

    // -------------------------------------------------------------------------
    // Silence
    // -------------------------------------------------------------------------

    @Test
    void billWithNoMatchRows_isNeverMatched() {
        when(matches.countComparedLinesByBillIds(any())).thenReturn(List.of());

        assertThat(reader.stateFor(1L, 3)).isEqualTo(BillComparisonState.NEVER_MATCHED);
    }

    @Test
    void billMissingFromASnapshot_isNeverMatched() {
        when(matches.countComparedLinesByBillIds(any()))
                .thenReturn(List.of(counts(1L, 2, 2)));

        BillComparisonReader.Snapshot snapshot = reader.snapshotFor(List.of(1L, 2L));

        assertThat(snapshot.stateFor(2L, 1)).isEqualTo(BillComparisonState.NEVER_MATCHED);
    }

    @Test
    void unsavedBill_isNeverMatched_withoutTouchingTheDatabase() {
        assertThat(reader.stateFor(null, 1)).isEqualTo(BillComparisonState.NEVER_MATCHED);
        verify(matches, never()).countComparedLinesByBillIds(any());
    }

    @Test
    void emptySnapshot_answersNeverMatchedForEveryBill() {
        assertThat(reader.snapshotFor(List.of()).stateFor(7L, 4))
                .isEqualTo(BillComparisonState.NEVER_MATCHED);
        assertThat(reader.snapshotFor(null).stateFor(7L, 4))
                .isEqualTo(BillComparisonState.NEVER_MATCHED);
        verify(matches, never()).countComparedLinesByBillIds(any());
    }

    // -------------------------------------------------------------------------
    // The roll-up
    // -------------------------------------------------------------------------

    @Test
    void everyLineCompared_isFullyCompared() {
        when(matches.countComparedLinesByBillIds(any())).thenReturn(List.of(counts(1L, 3, 3)));

        assertThat(reader.stateFor(1L, 3)).isEqualTo(BillComparisonState.ALL_LINES_COMPARED);
        assertThat(BillComparisonState.ALL_LINES_COMPARED.isFullyCompared()).isTrue();
        assertThat(BillComparisonState.ALL_LINES_COMPARED.needsReview()).isFalse();
    }

    @Test
    void matchRanButComparedNothing_isNoLinesCompared() {
        when(matches.countComparedLinesByBillIds(any())).thenReturn(List.of(counts(1L, 2, 0)));

        assertThat(reader.stateFor(1L, 2)).isEqualTo(BillComparisonState.NO_LINES_COMPARED);
        assertThat(BillComparisonState.NO_LINES_COMPARED.needsReview()).isTrue();
    }

    @Test
    void partOfTheBillCompared_isSomeLinesCompared() {
        when(matches.countComparedLinesByBillIds(any())).thenReturn(List.of(counts(1L, 3, 1)));

        assertThat(reader.stateFor(1L, 3)).isEqualTo(BillComparisonState.SOME_LINES_COMPARED);
        assertThat(BillComparisonState.SOME_LINES_COMPARED.needsReview()).isTrue();
    }

    @Test
    void aLineWithNoMatchRowAtAll_stillCountsAsUnchecked() {
        // 3 lines, but only 2 match rows and both compared. The third line was never looked at, so
        // the bill is NOT fully compared — comparing against the LINE count, not the row count, is
        // what stops a missing row disappearing.
        when(matches.countComparedLinesByBillIds(any())).thenReturn(List.of(counts(1L, 2, 2)));

        assertThat(reader.stateFor(1L, 3)).isEqualTo(BillComparisonState.SOME_LINES_COMPARED);
    }

    @Test
    void billWithNoLines_isNotReportedAsCompared() {
        when(matches.countComparedLinesByBillIds(any())).thenReturn(List.of(counts(1L, 1, 1)));

        assertThat(reader.stateFor(1L, 0)).isEqualTo(BillComparisonState.NO_LINES_COMPARED);
    }

    @Test
    void nullCountsFromTheDatabaseDoNotBecomeAPass() {
        when(matches.countComparedLinesByBillIds(any()))
                .thenReturn(List.of(counts(1L, null, null)));

        assertThat(reader.stateFor(1L, 2)).isEqualTo(BillComparisonState.NEVER_MATCHED);
    }

    // -------------------------------------------------------------------------
    // Page reads
    // -------------------------------------------------------------------------

    @Test
    void snapshotIgnoresNullsAndDuplicates() {
        when(matches.countComparedLinesByBillIds(any())).thenReturn(List.of(counts(1L, 1, 1)));

        BillComparisonReader.Snapshot snapshot =
                reader.snapshotFor(Arrays.asList(1L, 1L, null, 2L));

        assertThat(snapshot.stateFor(1L, 1)).isEqualTo(BillComparisonState.ALL_LINES_COMPARED);
        assertThat(snapshot.stateFor(2L, 1)).isEqualTo(BillComparisonState.NEVER_MATCHED);
    }

    @Test
    void snapshotOfOnlyNullIds_readsNothing() {
        assertThat(reader.snapshotFor(Arrays.asList((Long) null, null).stream().toList())
                .stateFor(1L, 1)).isEqualTo(BillComparisonState.NEVER_MATCHED);
        verify(matches, never()).countComparedLinesByBillIds(any());
    }

    // -------------------------------------------------------------------------

    private static BillComparisonCounts counts(Long billId, Integer matchCount,
                                                Integer comparedCount) {
        return new BillComparisonCounts() {
            @Override public Long getBillId()        { return billId; }
            @Override public Long getMatchCount()    { return matchCount == null ? null : matchCount.longValue(); }
            @Override public Long getComparedCount() { return comparedCount == null ? null : comparedCount.longValue(); }
        };
    }
}
