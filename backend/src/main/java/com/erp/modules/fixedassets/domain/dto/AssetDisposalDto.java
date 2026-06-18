package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.AssetDisposalType;
import java.math.BigDecimal;
import java.time.LocalDate;

public record AssetDisposalDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long fixedAssetId,
        AssetDisposalType disposalType,
        LocalDate disposalDate,
        Long fiscalPeriodId,
        BigDecimal proceedsAmount,
        BigDecimal nbvAtDisposal,
        BigDecimal gainLossAmount,
        String glEntryUid,
        String currency,
        String reason,
        // P2 D7 — buyer + provenance + approval
        String buyerName,
        Long buyerId,
        String proceedsArInvoiceUid,
        Long approvedBy
) {}
