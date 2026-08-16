package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Everything needed to provision a tenant (ADR-0062 P5-2).
 *
 * <p>The administrator's password is accepted rather than generated, so the platform operator can
 * hand it over out of band; it is never returned, logged or echoed. The 12-character floor mirrors
 * {@code ERP_BOOTSTRAP_ADMIN_PASSWORD}'s — a tenant created through the API must not be weaker than
 * one created by bootstrap.
 *
 * <p>Currency fields are optional and fall back to the deployment defaults, matching what bootstrap
 * does for the first tenant.
 *
 * <h2>The optional fields below are optional in a different sense</h2>
 *
 * Currency and time zone fall back to a deployment default when blank. The tax identity, price
 * list, walk-in customer and till do NOT: a blank one creates <b>nothing</b>. These are values a
 * human knows — a TIN, the name the tenant calls its price list, what is written on the till — and
 * a default chosen in Java would be a wrong answer that nobody can tell from a right one. A tenant
 * created without a TIN gets no TIN and fills it in on the Company screen; a tenant created without
 * a price-list name gets no price list, rather than one named "Standard" that somebody then has to
 * find and delete.
 *
 * <p>Length is the only validation. There is deliberately no {@code @Pattern} on the tax fields:
 * a TRA TIN is conventionally {@code 123-456-789} and a VRN {@code 40-XXXXXX-A}, but the estate is
 * multi-country by construction (the currency seeder enables KES/UGX/RWF/BIF/SSP), so a
 * Tanzania-shaped regex would reject a legitimate Kenyan tenant at the one moment nobody can debug
 * it. {@code UpdateCompanyRequest} constrains the same three columns by length alone.
 */
public record CreateTenantRequest(
        @NotBlank @Size(max = 160) String organisationName,
        @Size(max = 64) String timeZone,
        @NotBlank @Size(max = 20) String companyCode,
        @NotBlank @Size(max = 160) String companyName,

        /** Registered legal name, if it differs from the trading name. Blank ⇒ not recorded. */
        @Size(max = 200) String companyLegalName,

        /**
         * Taxpayer identification number. Blank ⇒ not recorded, and every printed document then
         * omits its TIN line rather than printing an invented one — which is not a valid tax
         * invoice, so supply it if the tenant has one.
         */
        @Size(max = 60) String companyTaxId,

        /**
         * VAT registration number. Reaches the Company screen and every standard report header;
         * it cannot reach a printed invoice, because {@code document_branding} has no {@code vrn}
         * column (V19) and adding one is a schema change.
         */
        @Size(max = 40) String companyVrn,

        @NotBlank @Size(max = 20) String branchCode,
        @NotBlank @Size(max = 160) String branchName,
        @NotBlank @Size(max = 60) String adminUsername,
        @NotBlank @Size(min = 12, max = 200) String adminPassword,
        @Size(max = 160) String adminDisplayName,
        @Size(max = 3) String baseCurrency,
        @Size(max = 3) String defaultCurrency,
        List<String> enabledCurrencies,

        /**
         * Short mnemonic for the tenant's first price list, e.g. {@code RETAIL}. Supply it together
         * with {@link #priceListName()} or omit both — {@code price_lists.code} is NOT NULL and
         * deriving one from the name would be inventing a value the operator did not give.
         */
        @Size(max = 20) String priceListCode,

        /**
         * Name of the tenant's first price list, e.g. {@code Retail}. Blank ⇒ no price list, and
         * until someone creates one the POS renders every product as unpriced.
         */
        @Size(max = 120) String priceListName,

        /**
         * Whether the prices entered against that list are VAT-INCLUSIVE — {@code true} for a
         * typical Tanzanian retailer, whose shelf price is what the customer pays.
         *
         * <p>Ask the operator. It is not a preference: an exclusive list holding gross prices has
         * 18% ADDED to every line at invoicing, and {@code sales_invoice_lines.price_inclusive} is
         * an immutable per-line snapshot, so flipping the flag later corrects nothing already sold.
         *
         * <p>Omitted ⇒ the list is VAT-EXCLUSIVE, which is what {@code POST /api/v1/price-lists}
         * creates with the flag omitted (ADR-0056 D-2). Null when no price list was asked for.
         */
        Boolean priceListIncludesVat,

        /**
         * Display name for the counter customer every cash sale is rung against, e.g.
         * {@code Walk-in Customer}. Blank ⇒ none is created.
         */
        @Size(max = 200) String walkInCustomerName,

        /**
         * Name of the tenant's first POS till, on the default branch.
         *
         * <p>Till names are unique across the WHOLE COMPANY, not per branch
         * ({@code uq_pos_till_company_name}), so "Till 1" in the head office blocks "Till 1" in the
         * second shop. Whatever is typed here is the pattern the tenant copies when it opens branch
         * two, so a branch-qualified name — "HQ Till 1", "Main Counter 1" — is worth the extra word.
         *
         * <p>Blank ⇒ no till, and no session can be opened until someone adds one.
         */
        @Size(max = 60) String posTillName) {
}
