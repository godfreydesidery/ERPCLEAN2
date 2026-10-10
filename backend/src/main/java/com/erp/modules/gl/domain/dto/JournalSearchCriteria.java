package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.JournalSourceType;
import java.time.LocalDate;

/**
 * Optional filters of the journal list (ACC-19). Every field may be null (no filter).
 *
 * @param from       earliest posting date (inclusive)
 * @param to         latest posting date (inclusive)
 * @param sourceType journals of this source type only
 * @param accountUid journals with at least one line on this account (company-scoped)
 * @param q          free text: description, batch number, source/external ref or line memo; a
 *                   document number also finds the other journals of the same source document
 */
public record JournalSearchCriteria(
        LocalDate from,
        LocalDate to,
        JournalSourceType sourceType,
        String accountUid,
        String q
) {

    /** True when no filter is set. */
    public boolean isEmpty() {
        return from == null && to == null && sourceType == null
                && (accountUid == null || accountUid.isBlank())
                && (q == null || q.isBlank());
    }
}
