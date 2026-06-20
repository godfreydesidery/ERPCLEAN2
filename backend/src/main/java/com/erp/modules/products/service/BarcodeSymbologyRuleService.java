package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.BarcodeSymbologyRuleDto;
import com.erp.modules.products.domain.dto.CreateBarcodeSymbologyRuleRequest;
import com.erp.modules.products.domain.dto.EmbeddedBarcodeDecode;
import com.erp.modules.products.domain.dto.UpdateBarcodeSymbologyRuleRequest;
import java.util.List;
import java.util.Optional;

/**
 * CRUD administration and decode hot-path for barcode symbology rules (ADR-0044 D-1a / BR-9).
 */
public interface BarcodeSymbologyRuleService {

    BarcodeSymbologyRuleDto create(CreateBarcodeSymbologyRuleRequest req);

    BarcodeSymbologyRuleDto getByUid(String uid);

    List<BarcodeSymbologyRuleDto> listByCompany(Long companyId);

    BarcodeSymbologyRuleDto updateByUid(String uid, UpdateBarcodeSymbologyRuleRequest req);

    void archiveByUid(String uid);

    /**
     * Decode a raw barcode string against the company's active symbology rules.
     *
     * <p>Rules are evaluated in ascending priority order. The first rule whose
     * {@code prefix} is a leading substring of {@code barcode} AND whose offsets fit within
     * the barcode's length is applied:
     * <ol>
     *   <li>Item code = {@code barcode.substring(itemCodeStart, itemCodeStart + itemCodeLength)}</li>
     *   <li>Raw value = {@code barcode.substring(valueStart, valueStart + valueLength)}</li>
     *   <li>Decoded value = {@code new BigDecimal(rawInteger).scaleByPowerOfTen(-valueDecimals)}</li>
     * </ol>
     *
     * @param companyId owning company (tenant scope)
     * @param barcode   raw scanned barcode string
     * @return the decode result, or empty if no rule matches or the barcode is too short
     */
    Optional<EmbeddedBarcodeDecode> decode(Long companyId, String barcode);
}
