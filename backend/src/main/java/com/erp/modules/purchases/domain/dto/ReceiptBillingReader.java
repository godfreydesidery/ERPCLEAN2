package com.erp.modules.purchases.domain.dto;

import java.util.Collection;
import java.util.List;

/**
 * Port: "has any supplier bill claimed these goods-receipt lines?" (PUR-04 / LBO-04).
 *
 * <p>Lives in {@code purchases.domain.dto} so the purchases module can ask without importing AP; the
 * implementation lives in {@code ap.service} and reads {@code supplier_bill_lines.gr_line_uid}. A
 * bill that claims a receipt line keeps the supplier owed for those goods, so voiding the receipt
 * would leave AP owing for stock that is gone.
 */
public interface ReceiptBillingReader {

    /**
     * The supplier invoice numbers of bills in {@code companyId} with a line claiming any of
     * {@code grLineUids}; empty when none.
     */
    List<String> billsClaimingReceiptLines(Long companyId, Collection<String> grLineUids);
}
