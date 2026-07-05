package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.dto.MoneyDto;
import com.erp.modules.parties.domain.dto.UpdateCustomerRequest;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.CustomerSegment;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.bulk.BulkImportHandler;
import com.erp.platform.bulk.ColumnSpec;
import com.erp.platform.bulk.ImportContext;
import com.erp.platform.bulk.ImportMode;
import com.erp.platform.bulk.ImportParsers;
import com.erp.platform.bulk.ImportRow;
import com.erp.platform.bulk.RowOutcome;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk-import handler for customers. Maps a spreadsheet row onto {@link CustomerService} create /
 * update (same validation, scope, code generation and audit as manual entry). Upsert by customer
 * code: blank creates (code auto-assigned); a matching code updates (blank optional cells keep the
 * current value). Supports the download → edit → re-upload round-trip via {@link #exportRows}.
 */
@Component
@Transactional
public class CustomerImportHandler implements BulkImportHandler {

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
    private static final String COL_KIND = "Customer Kind";
    private static final String COL_CREDIT_AMOUNT = "Credit Limit Amount";
    private static final String COL_CREDIT_CURRENCY = "Credit Limit Currency";
    private static final String COL_TERMS_DAYS = "Payment Terms Days";
    private static final String COL_SEGMENT = "Segment";
    private static final String COL_COUNTRY = "Country";
    private static final String COL_CURRENCY = "Default Currency";
    private static final String COL_TAX_EXEMPT = "Tax Exempt";

    private final CustomerService customerService;
    private final CustomerRepository customers;

    public CustomerImportHandler(CustomerService customerService, CustomerRepository customers) {
        this.customerService = customerService;
        this.customers = customers;
    }

    @Override
    public String key() {
        return "customers";
    }

    @Override
    public String displayName() {
        return "Customers";
    }

    @Override
    public String permissionCode() {
        return "CUSTOMER.IMPORT";
    }

    @Override
    public List<ColumnSpec> columns(Long companyId) {
        List<String> yesNo = List.of("Yes", "No");
        return List.of(
                ColumnSpec.of(COL_CODE, false,
                        "Leave blank to auto-generate. Fill in to update an existing customer."),
                ColumnSpec.choice(COL_PARTY_TYPE, true, "A person or an organisation.",
                        List.of(PartyType.INDIVIDUAL.name(), PartyType.BUSINESS.name())),
                ColumnSpec.of(COL_DISPLAY_NAME, true, "Name shown on invoices and lists."),
                ColumnSpec.of(COL_LEGAL_NAME, false, "Registered legal name, if different."),
                ColumnSpec.of(COL_TIN, false, "Tax Identification Number. Required for a BUSINESS party type."),
                ColumnSpec.choice(COL_VAT_REG, false, "Is the customer VAT-registered?", yesNo),
                ColumnSpec.of(COL_VRN, false, "VAT Registration Number. Only allowed if VAT-registered is Yes."),
                ColumnSpec.of(COL_PHONE, false, "Contact phone."),
                ColumnSpec.of(COL_EMAIL, false, "Contact email."),
                ColumnSpec.of(COL_ADDRESS, false, "Physical address."),
                ColumnSpec.of(COL_REGION, false, "Region."),
                ColumnSpec.of(COL_DISTRICT, false, "District."),
                ColumnSpec.choice(COL_KIND, true, "Walk-in (cash) or a credit account.",
                        List.of(CustomerKind.CASH_WALK_IN.name(), CustomerKind.CREDIT_ACCOUNT.name())),
                ColumnSpec.of(COL_CREDIT_AMOUNT, false, "Credit limit. Requires a currency when set."),
                ColumnSpec.of(COL_CREDIT_CURRENCY, false, "3-letter code, e.g. TZS."),
                ColumnSpec.of(COL_TERMS_DAYS, false, "Payment terms in days."),
                ColumnSpec.choice(COL_SEGMENT, false, "Commercial segment.",
                        List.of(CustomerSegment.RETAIL.name(), CustomerSegment.WHOLESALE.name(),
                                CustomerSegment.GOVERNMENT.name(), CustomerSegment.OTHER.name())),
                ColumnSpec.of(COL_COUNTRY, false, "2-letter ISO country code, e.g. TZ."),
                ColumnSpec.of(COL_CURRENCY, false, "Default currency (3-letter code)."),
                ColumnSpec.choice(COL_TAX_EXEMPT, false, "Is the customer tax-exempt?", yesNo));
    }

    @Override
    public RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx) {
        String code = row.get(COL_CODE);
        Customer existing = code.isEmpty() ? null : findByCode(companyId, code);

        if (existing != null) {
            customerService.updateByUid(existing.getUid(), buildUpdate(existing, row));
            return RowOutcome.update(row.rowNumber(), existing.getCode());
        }
        CustomerDto created = customerService.create(buildCreate(companyId, row));
        return RowOutcome.create(row.rowNumber(), created.code());
    }

    private Customer findByCode(Long companyId, String code) {
        String c = code.trim();
        return customers.findByCompanyIdAndCode(companyId, c)
                .or(() -> customers.findByCompanyIdAndCode(companyId, c.toUpperCase()))
                .orElse(null);
    }

    private CreateCustomerRequest buildCreate(Long companyId, ImportRow row) {
        return new CreateCustomerRequest(
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
                ImportParsers.parseEnumRequired(CustomerKind.class, row, COL_KIND),
                money(row),
                ImportParsers.parseInt(row, COL_TERMS_DAYS),
                null, // paymentTermsId — not in template
                ImportParsers.isoCode2(row, COL_COUNTRY),
                null, // defaultPriceListId — not in template
                null, // defaultAgentId — not in template
                ImportParsers.parseEnum(CustomerSegment.class, row, COL_SEGMENT, null),
                ImportParsers.parseBool(row, COL_TAX_EXEMPT, null),
                null, // taxExemptionRef — not in template
                ImportParsers.text(row, COL_CURRENCY));
    }

    private UpdateCustomerRequest buildUpdate(Customer existing, ImportRow row) {
        CustomerDto cur = CustomerDto.from(existing);
        return new UpdateCustomerRequest(
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
                row.has(COL_KIND) ? ImportParsers.parseEnumRequired(CustomerKind.class, row, COL_KIND) : cur.customerKind(),
                row.has(COL_CREDIT_AMOUNT) ? money(row) : cur.creditLimit(),
                row.has(COL_TERMS_DAYS) ? ImportParsers.parseInt(row, COL_TERMS_DAYS) : cur.paymentTermsDays(),
                cur.paymentTermsId(),
                row.has(COL_COUNTRY) ? ImportParsers.isoCode2(row, COL_COUNTRY) : cur.country(),
                cur.defaultPriceListId(),
                cur.defaultAgentId(),
                row.has(COL_SEGMENT) ? ImportParsers.parseEnum(CustomerSegment.class, row, COL_SEGMENT, cur.segment()) : cur.segment(),
                row.has(COL_TAX_EXEMPT) ? ImportParsers.parseBool(row, COL_TAX_EXEMPT, cur.taxExempt()) : cur.taxExempt(),
                cur.taxExemptionRef(),
                row.has(COL_CURRENCY) ? ImportParsers.text(row, COL_CURRENCY) : cur.defaultCurrency());
    }

    @Override
    public List<LinkedHashMap<String, String>> exportRows(Long companyId, Map<String, String> params) {
        List<LinkedHashMap<String, String>> rows = new ArrayList<>();
        for (Customer c : customers.findByCompanyId(companyId, Pageable.unpaged()).getContent()) {
            if (c.getStatus() != MasterStatus.ACTIVE) {
                continue;
            }
            CustomerDto d = CustomerDto.from(c);
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
            r.put(COL_KIND, d.customerKind() != null ? d.customerKind().name() : "");
            r.put(COL_CREDIT_AMOUNT, d.creditLimit() != null ? d.creditLimit().amount() : "");
            r.put(COL_CREDIT_CURRENCY, d.creditLimit() != null ? d.creditLimit().currency() : "");
            r.put(COL_TERMS_DAYS, d.paymentTermsDays() != null ? d.paymentTermsDays().toString() : "");
            r.put(COL_SEGMENT, d.segment() != null ? d.segment().name() : "");
            r.put(COL_COUNTRY, nz(d.country()));
            r.put(COL_CURRENCY, nz(d.defaultCurrency()));
            r.put(COL_TAX_EXEMPT, yn(d.taxExempt()));
            rows.add(r);
            if (rows.size() >= EXPORT_MAX) {
                break;
            }
        }
        return rows;
    }

    private static MoneyDto money(ImportRow row) {
        BigDecimal amount = ImportParsers.parseDecimal(row, COL_CREDIT_AMOUNT);
        if (amount == null) {
            return null;
        }
        String currency = row.get(COL_CREDIT_CURRENCY);
        if (currency.isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + COL_CREDIT_CURRENCY + "' is required when '" + COL_CREDIT_AMOUNT + "' is set.");
        }
        return new MoneyDto(amount.toPlainString(), currency.toUpperCase());
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String yn(boolean b) {
        return b ? "Yes" : "No";
    }
}
