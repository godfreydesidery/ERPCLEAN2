package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import java.util.List;

/**
 * Fixed Asset Register as at a date (FR-FA-17).
 *
 * <p>Rows list every matching asset acquired on or before {@code asOf}. Totals (per category and
 * grand) cover only the assets in service at that date; {@code rowsNotInTotals} counts the listed
 * draft / disposed / written-off assets the totals leave out, so the reader is told rather than left
 * to assume the totals cover every row.
 */
public record FixedAssetRegisterDto(
        String asOf,
        String categoryName,
        String branchName,
        FixedAssetStatus status,
        String location,
        String costCentreName,
        List<FixedAssetRegisterRowDto> rows,
        List<FixedAssetRegisterTotalDto> categoryTotals,
        FixedAssetRegisterTotalDto grandTotal,
        int rowsNotInTotals,
        String currency,
        String generatedAt
) {}
