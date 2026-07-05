package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.dto.UpdateSupplierRequest;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.platform.bulk.BulkImportHandler;
import com.erp.platform.bulk.ColumnSpec;
import com.erp.platform.bulk.ImportContext;
import com.erp.platform.bulk.ImportMode;
import com.erp.platform.bulk.ImportParsers;
import com.erp.platform.bulk.ImportRow;
import com.erp.platform.bulk.RowOutcome;
import com.erp.platform.common.domain.MasterStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk-import handler for suppliers. Maps a spreadsheet row onto {@link SupplierService} create /
 * update (same validation, scope, code generation and audit as manual entry). Upsert by supplier
 * code: blank creates (code auto-assigned); a matching code updates (blank optional cells keep the
 * current value). Supports the download → edit → re-upload round-trip via {@link #exportRows}.
 */
@Component
@Transactional
public class SupplierImportHandler implements BulkImportHandler {

    private static final int EXPORT_MAX = 2000;

    private static final String COL_CODE = "Code";
    private static final String COL_PARTY_TYPE = "Party Type";
    private static final String COL_DISPLAY_NAME = "Display Name";
    private static final String COL_LEGAL_NAME = "Legal Name";
    private static final String COL_TIN = "TIN";
    private static final String COL_VAT_REG = "VAT Registered";
    private static final String COL_VRN = "VRN";
    private static final String COL_PHONE = "Phone";
    private static final String COL_EMAIL = "Email";
    private static final String COL_ADDRESS = "Physical Address";
    private static final String COL_REGION = "Region";
    private static final String COL_DISTRICT = "District";
    private static final String COL_KIND = "Supplier Kind";
    private static final String COL_TERMS_DAYS = "Payment Terms Days";
    private static final String COL_COUNTRY = "Country";
    private static final String COL_CURRENCY = "Default Currency";
    private static final String COL_LEAD_TIME = "Lead Time Days";
    private static final String COL_MIN_ORDER = "Min Order Value";

    private final SupplierService supplierService;
    private final SupplierRepository suppliers;

    public SupplierImportHandler(SupplierService supplierService, SupplierRepository suppliers) {
        this.supplierService = supplierService;
        this.suppliers = suppliers;
    }

    @Override
    public String key() {
        return "suppliers";
    }

    @Override
    public String displayName() {
        return "Suppliers";
    }

    @Override
    public String permissionCode() {
        return "SUPPLIER.IMPORT";
    }

    @Override
    public List<ColumnSpec> columns(Long companyId) {
        List<String> yesNo = List.of("Yes", "No");
        return List.of(
                ColumnSpec.of(COL_CODE, false,
                        "Leave blank to auto-generate. Fill in to update an existing supplier."),
                ColumnSpec.choice(COL_PARTY_TYPE, true, "A person or an organisation.",
                        List.of(PartyType.INDIVIDUAL.name(), PartyType.BUSINESS.name())),
                ColumnSpec.of(COL_DISPLAY_NAME, true, "Name shown on orders and lists."),
                ColumnSpec.of(COL_LEGAL_NAME, false, "Registered legal name, if different."),
                ColumnSpec.of(COL_TIN, false, "Tax Identification Number."),
                ColumnSpec.choice(COL_VAT_REG, false, "Is the supplier VAT-registered?", yesNo),
                ColumnSpec.of(COL_VRN, false, "VAT Registration Number. Only allowed if VAT-registered is Yes."),
                ColumnSpec.of(COL_PHONE, false, "Contact phone."),
                ColumnSpec.of(COL_EMAIL, false, "Contact email."),
                ColumnSpec.of(COL_ADDRESS, false, "Physical address."),
                ColumnSpec.of(COL_REGION, false, "Region."),
                ColumnSpec.of(COL_DISTRICT, false, "District."),
                ColumnSpec.choice(COL_KIND, true, "What the supplier provides.",
                        List.of(SupplierKind.GOODS.name(), SupplierKind.SERVICE.name())),
                ColumnSpec.of(COL_TERMS_DAYS, false, "Payment terms in days."),
                ColumnSpec.of(COL_COUNTRY, false, "2-letter ISO country code, e.g. TZ."),
                ColumnSpec.of(COL_CURRENCY, false, "Default currency (3-letter code)."),
                ColumnSpec.of(COL_LEAD_TIME, false, "Typical supply lead time in days."),
                ColumnSpec.of(COL_MIN_ORDER, false, "Minimum order value."));
    }

    @Override
    public RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx) {
        String code = row.get(COL_CODE);
        Supplier existing = code.isEmpty() ? null : findByCode(companyId, code);

        if (existing != null) {
            supplierService.updateByUid(existing.getUid(), buildUpdate(existing, row));
            return RowOutcome.update(row.rowNumber(), existing.getCode());
        }
        SupplierDto created = supplierService.create(buildCreate(companyId, row));
        return RowOutcome.create(row.rowNumber(), created.code());
    }

    private Supplier findByCode(Long companyId, String code) {
        String c = code.trim();
        return suppliers.findByCompanyIdAndCode(companyId, c)
                .or(() -> suppliers.findByCompanyIdAndCode(companyId, c.toUpperCase()))
                .orElse(null);
    }

    private CreateSupplierRequest buildCreate(Long companyId, ImportRow row) {
        return new CreateSupplierRequest(
                companyId,
                ImportParsers.parseEnumRequired(PartyType.class, row, COL_PARTY_TYPE),
                ImportParsers.requireText(row, COL_DISPLAY_NAME),
                ImportParsers.text(row, COL_LEGAL_NAME),
                ImportParsers.text(row, COL_TIN),
                ImportParsers.parseBool(row, COL_VAT_REG, null),
                ImportParsers.text(row, COL_VRN),
                null, // businessRegNo — not in template
                null, // mobileMoneyNo — not in template
                ImportParsers.text(row, COL_PHONE),
                ImportParsers.text(row, COL_EMAIL),
                ImportParsers.text(row, COL_ADDRESS),
                null, // postalAddress — not in template
                ImportParsers.text(row, COL_REGION),
                ImportParsers.text(row, COL_DISTRICT),
                ImportParsers.parseEnumRequired(SupplierKind.class, row, COL_KIND),
                ImportParsers.parseInt(row, COL_TERMS_DAYS),
                null, // paymentTermsId — not in template
                ImportParsers.isoCode2(row, COL_COUNTRY),
                ImportParsers.text(row, COL_CURRENCY),
                ImportParsers.parseInt(row, COL_LEAD_TIME),
                ImportParsers.parseDecimal(row, COL_MIN_ORDER),
                null); // defaultWhtTypeId — not in template
    }

    private UpdateSupplierRequest buildUpdate(Supplier existing, ImportRow row) {
        SupplierDto cur = SupplierDto.from(existing);
        return new UpdateSupplierRequest(
                row.has(COL_PARTY_TYPE) ? ImportParsers.parseEnumRequired(PartyType.class, row, COL_PARTY_TYPE) : cur.partyType(),
                row.has(COL_DISPLAY_NAME) ? row.get(COL_DISPLAY_NAME) : cur.displayName(),
                row.has(COL_LEGAL_NAME) ? ImportParsers.text(row, COL_LEGAL_NAME) : cur.legalName(),
                row.has(COL_TIN) ? ImportParsers.text(row, COL_TIN) : cur.tin(),
                row.has(COL_VAT_REG) ? ImportParsers.parseBool(row, COL_VAT_REG, cur.vatRegistered()) : cur.vatRegistered(),
                row.has(COL_VRN) ? ImportParsers.text(row, COL_VRN) : cur.vrn(),
                cur.businessRegNo(),
                cur.mobileMoneyNo(),
                row.has(COL_PHONE) ? ImportParsers.text(row, COL_PHONE) : cur.phone(),
                row.has(COL_EMAIL) ? ImportParsers.text(row, COL_EMAIL) : cur.email(),
                row.has(COL_ADDRESS) ? ImportParsers.text(row, COL_ADDRESS) : cur.physicalAddress(),
                cur.postalAddress(),
                row.has(COL_REGION) ? ImportParsers.text(row, COL_REGION) : cur.region(),
                row.has(COL_DISTRICT) ? ImportParsers.text(row, COL_DISTRICT) : cur.district(),
                row.has(COL_KIND) ? ImportParsers.parseEnumRequired(SupplierKind.class, row, COL_KIND) : cur.supplierKind(),
                row.has(COL_TERMS_DAYS) ? ImportParsers.parseInt(row, COL_TERMS_DAYS) : cur.paymentTermsDays(),
                cur.paymentTermsId(),
                row.has(COL_COUNTRY) ? ImportParsers.isoCode2(row, COL_COUNTRY) : cur.country(),
                row.has(COL_CURRENCY) ? ImportParsers.text(row, COL_CURRENCY) : cur.defaultCurrency(),
                row.has(COL_LEAD_TIME) ? ImportParsers.parseInt(row, COL_LEAD_TIME) : cur.leadTimeDays(),
                row.has(COL_MIN_ORDER) ? ImportParsers.parseDecimal(row, COL_MIN_ORDER) : cur.minOrderValue(),
                cur.defaultWhtTypeId());
    }

    @Override
    public List<LinkedHashMap<String, String>> exportRows(Long companyId, Map<String, String> params) {
        List<LinkedHashMap<String, String>> rows = new ArrayList<>();
        for (Supplier s : suppliers.findByCompanyId(companyId, Pageable.unpaged()).getContent()) {
            if (s.getStatus() != MasterStatus.ACTIVE) {
                continue;
            }
            SupplierDto d = SupplierDto.from(s);
            LinkedHashMap<String, String> r = new LinkedHashMap<>();
            r.put(COL_CODE, nz(d.code()));
            r.put(COL_PARTY_TYPE, d.partyType() != null ? d.partyType().name() : "");
            r.put(COL_DISPLAY_NAME, nz(d.displayName()));
            r.put(COL_LEGAL_NAME, nz(d.legalName()));
            r.put(COL_TIN, nz(d.tin()));
            r.put(COL_VAT_REG, yn(d.vatRegistered()));
            r.put(COL_VRN, nz(d.vrn()));
            r.put(COL_PHONE, nz(d.phone()));
            r.put(COL_EMAIL, nz(d.email()));
            r.put(COL_ADDRESS, nz(d.physicalAddress()));
            r.put(COL_REGION, nz(d.region()));
            r.put(COL_DISTRICT, nz(d.district()));
            r.put(COL_KIND, d.supplierKind() != null ? d.supplierKind().name() : "");
            r.put(COL_TERMS_DAYS, d.paymentTermsDays() != null ? d.paymentTermsDays().toString() : "");
            r.put(COL_COUNTRY, nz(d.country()));
            r.put(COL_CURRENCY, nz(d.defaultCurrency()));
            r.put(COL_LEAD_TIME, d.leadTimeDays() != null ? d.leadTimeDays().toString() : "");
            r.put(COL_MIN_ORDER, d.minOrderValue() != null ? d.minOrderValue().toPlainString() : "");
            rows.add(r);
            if (rows.size() >= EXPORT_MAX) {
                break;
            }
        }
        return rows;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String yn(boolean b) {
        return b ? "Yes" : "No";
    }
}
