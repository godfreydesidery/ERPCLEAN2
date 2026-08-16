package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a brand-new tenant's counter customer — the party every cash sale is rung against —
 * from a display name the operator supplied (ADR-0062 P5-2).
 *
 * <p>Lives in the parties module rather than inline in {@code TenantProvisioningService} because
 * the shape of the row is a parties rule: BR-PARTY-04 (implemented in
 * {@code CustomerServiceImpl.validateIdentifiers}) refuses a BUSINESS customer with no TIN, and a
 * walk-in has none by definition. Building the row in a platform class would put a second writer of
 * that rule where nobody maintaining it will ever look.
 *
 * <p>Named {@code createIfNamed} rather than {@code seedDefaults} deliberately — see
 * {@code DefaultPriceListProvisioner} for why the seeder shape is the one thing this must not have.
 */
@Component
public class WalkInCustomerProvisioner {

    private static final Logger log = LoggerFactory.getLogger(WalkInCustomerProvisioner.class);

    private final CustomerRepository customers;
    private final PartyCodeGenerator codeGenerator;

    public WalkInCustomerProvisioner(CustomerRepository customers, PartyCodeGenerator codeGenerator) {
        this.customers = customers;
        this.codeGenerator = codeGenerator;
    }

    /**
     * Creates the counter customer when the operator named one.
     *
     * <p>Only the display name comes from the request. Everything else is entailed rather than
     * chosen: {@link PartyType#INDIVIDUAL} because BR-PARTY-04 refuses a BUSINESS party without a
     * TIN, and {@link CustomerKind#CASH_WALK_IN} because that is what makes a sale settle as cash
     * instead of raising an AR open item and skipping the credit gate. The credit limit is left
     * null on both halves — the entity documents it as null for this kind, and
     * {@code chk_customer_credit_pair} requires both columns null or both set.
     *
     * <p>{@code created_by} stays null, matching {@code StockLocationSeeder} and
     * {@code CashBankSeeder}: there is no human actor inside provisioning, and the operator who
     * asked for the tenant is already on the ORG_CREATE audit row.
     *
     * @return the new customer's id, or {@code null} when no name was supplied
     */
    @Transactional
    public Long createIfNamed(Long companyId, String displayName) {
        // Guarded here and not only at the call site: bootstrap reaches this method with a null,
        // and that path must be provably inert without reading the caller.
        if (displayName == null || displayName.isBlank()) {
            return null;
        }

        String code = codeGenerator.next(companyId, "CUSTOMER");
        Customer customer = new Customer(companyId, code, PartyType.INDIVIDUAL, displayName.strip(),
                CustomerKind.CASH_WALK_IN, null);
        customers.save(customer);

        log.info("Provisioned walk-in customer '{}' for company {}.", code, companyId);
        return customer.getId();
    }
}
