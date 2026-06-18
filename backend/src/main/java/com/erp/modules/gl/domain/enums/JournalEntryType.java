package com.erp.modules.gl.domain.enums;

/**
 * Classification of a journal entry (P3 — convenience/reporting only; does not drive posting).
 *
 * <p>Orthogonal to {@link JournalSourceType} (which records the originating poster/module):
 * {@code entryType} captures the accounting <em>nature</em> of the entry for reporting/filtering.
 * Nullable on the entity — legacy/unset entries carry no type. The DB CHECK
 * ({@code chk_journal_entry_entry_type}) admits exactly this set.
 */
public enum JournalEntryType {
    GENERAL,
    ADJUSTING,
    REVERSING,
    OPENING,
    CLOSING,
    ACCRUAL
}
