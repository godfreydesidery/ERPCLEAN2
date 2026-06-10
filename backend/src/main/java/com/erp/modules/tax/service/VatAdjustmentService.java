package com.erp.modules.tax.service;

import com.erp.modules.tax.domain.dto.AddVatAdjustmentRequest;
import com.erp.modules.tax.domain.dto.VatAdjustmentDto;
import java.util.List;

/**
 * Manual VAT adjustment lifecycle (ADR-0017 D-3, FR-VAT-05/06).
 * Adjustments may only be added/removed on DRAFT returns (BR-VAT-09).
 */
public interface VatAdjustmentService {

    /** Add an adjustment to a DRAFT VAT return (FR-VAT-05). */
    VatAdjustmentDto addAdjustment(String vatReturnUid, AddVatAdjustmentRequest req);

    /** Remove an adjustment from a DRAFT VAT return (FR-VAT-06). */
    void removeAdjustment(String vatReturnUid, String adjustmentUid);

    /** List all adjustments for a return. */
    List<VatAdjustmentDto> listByReturn(String vatReturnUid);
}
