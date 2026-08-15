package com.erp.modules.products.service;

import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.repository.PriceListRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a brand-new tenant's FIRST price list, from a code and name the operator supplied
 * (ADR-0062 P5-2). Nothing here is defaulted, derived or invented.
 *
 * <h2>Why this is not a seeder</h2>
 *
 * Every other per-company default is a {@code *Seeder} with a {@code seedDefaults(companyId)}
 * signature, invoked from {@code CompanyProvisioningServiceImpl.provisionDefaults} — which is also
 * the <em>heal</em> path, run on demand against companies that have traded for years. A price list
 * has a name only a human knows, so there is nothing for a heal to seed; and a seeder-shaped
 * signature is an open invitation to add it to that chain, which is exactly the regression this
 * design exists to prevent. Hence {@code createIfNamed} and a required human-supplied name, and
 * hence the single call site in {@code TenantProvisioningService} — pinned by an ArchUnit rule.
 */
@Component
public class DefaultPriceListProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DefaultPriceListProvisioner.class);

    private final PriceListRepository priceLists;

    public DefaultPriceListProvisioner(PriceListRepository priceLists) {
        this.priceLists = priceLists;
    }

    /**
     * Creates the company's first price list when the operator named one.
     *
     * <p>Plain {@code @Transactional} (REQUIRED) so it joins the caller's tenant-creation
     * transaction: a price list that survived a rolled-back tenant would belong to a company that
     * does not exist.
     *
     * @param companyId    the company created moments earlier in this transaction
     * @param code         short mnemonic, e.g. {@code RETAIL}; blank with a blank name ⇒ nothing
     * @param name         display name, e.g. {@code Retail}; blank with a blank code ⇒ nothing
     * @param includesVat  whether the prices entered against this list are VAT-INCLUSIVE; null keeps
     *                     the entity default (exclusive), exactly as {@code POST /price-lists} does
     *                     with the flag omitted
     * @return the new price list's id, or {@code null} when none was requested
     * @throws IllegalArgumentException when exactly one of code/name was supplied
     */
    @Transactional
    public Long createIfNamed(Long companyId, String code, String name, Boolean includesVat) {
        boolean hasCode = code != null && !code.isBlank();
        boolean hasName = name != null && !name.isBlank();

        if (!hasCode && !hasName) {
            return null;
        }
        // Half a price list is not something to guess the other half of. price_lists.code is NOT
        // NULL, so a name on its own can only become a row by deriving a code — inventing a value
        // the operator did not give. Say so instead, before anything is created: the whole
        // provisioning transaction rolls back, so the operator retries with both and loses nothing.
        if (!hasCode || !hasName) {
            throw new IllegalArgumentException(
                    "A price list needs both a code and a name. Supply both, or leave both blank "
                            + "to create no price list.");
        }

        PriceList priceList = new PriceList(companyId, code.strip(), name.strip(), null);
        // The company's ONLY price list is its default. This is structural, not an invented
        // business value: pricing screens and the stock-valuation report resolve "the company
        // default", and a single unflagged list means they resolve nothing. There is exactly one
        // row, so nothing can collide, and a tenant that later wants a different default sets it
        // on the Price Lists screen.
        priceList.setDefault(true);
        // The VAT stance IS a human-known value, so it travels on the request like the name does.
        // Silence here would not be neutral: PriceList.priceIncludesVat initialises to false, so a
        // TZ retailer entering VAT-inclusive shelf prices against the list would have 18% ADDED on
        // top of every one of them (InvoiceTotalsCalculator), and price_inclusive is an immutable
        // per-line snapshot — flipping the flag afterwards corrects nothing already sold. Null keeps
        // the entity default rather than guessing, which is what PriceListServiceImpl.create does
        // with an omitted flag (ADR-0056 D-2: the SERVICE default stays exclusive and the create
        // screen sends true explicitly).
        if (includesVat != null) {
            priceList.setPriceIncludesVat(includesVat);
        }
        priceLists.save(priceList);

        log.info("Provisioned price list '{}' for company {}.", priceList.getCode(), companyId);
        return priceList.getId();
    }
}
