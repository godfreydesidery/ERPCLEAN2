package com.erp.platform.security;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.repository.OtherPartyRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.modules.sales.repository.TaxRateRepository;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ForbiddenException;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single home for tenant-scope enforcement (ADR-0002, building on ADR-0001 D-A). Answers one
 * question: may the caller act on a target that lives in company X? Yes iff the caller is root, or
 * the caller's active company equals X. Used by {@link PermissionChecks} for path-uid ops and
 * directly by services for body-scoped ops (grant/revoke, branch create), so the rule lives exactly
 * once and a forgotten call fails closed (returns/throws "not permitted").
 *
 * <p>Reading the Company/Branch/Party repositories from the security layer mirrors the established
 * {@link PermissionResolver} pattern (which reads {@code UserRoleRepository}); this is the
 * cross-cutting spine, not a peer module (ArchUnit note in ADR-0002 and ADR-0006 D-10).
 * Sales repositories are added here following the same pattern (ADR-0008 D-10).
 * Purchases repositories added per ADR-0011 D-10 ({@code purchaseorder}/{@code goodsreceipt}).
 */
@Component
public class ScopeGuard {

    private final CompanyRepository        companies;
    private final BranchRepository         branches;
    private final CustomerRepository       customers;
    private final SupplierRepository       suppliers;
    private final AgentRepository          agents;
    private final OtherPartyRepository     otherParties;
    private final ProductRepository        products;
    private final PriceListRepository      priceLists;
    private final UnitOfMeasureRepository  units;
    private final SalesInvoiceRepository   salesInvoices;
    private final TaxRateRepository        taxRates;
    private final StockOnHandRepository    stockOnHands;
    private final StockMovementRepository  stockMovements;
    private final PurchaseOrderRepository  purchaseOrders;
    private final GoodsReceiptRepository   goodsReceipts;
    private final AuditService             audit;

    public ScopeGuard(CompanyRepository companies,
                      BranchRepository branches,
                      CustomerRepository customers,
                      SupplierRepository suppliers,
                      AgentRepository agents,
                      OtherPartyRepository otherParties,
                      ProductRepository products,
                      PriceListRepository priceLists,
                      UnitOfMeasureRepository units,
                      SalesInvoiceRepository salesInvoices,
                      TaxRateRepository taxRates,
                      StockOnHandRepository stockOnHands,
                      StockMovementRepository stockMovements,
                      PurchaseOrderRepository purchaseOrders,
                      GoodsReceiptRepository goodsReceipts,
                      AuditService audit) {
        this.companies      = companies;
        this.branches       = branches;
        this.customers      = customers;
        this.suppliers      = suppliers;
        this.agents         = agents;
        this.otherParties   = otherParties;
        this.products       = products;
        this.priceLists     = priceLists;
        this.units          = units;
        this.salesInvoices  = salesInvoices;
        this.taxRates       = taxRates;
        this.stockOnHands   = stockOnHands;
        this.stockMovements = stockMovements;
        this.purchaseOrders = purchaseOrders;
        this.goodsReceipts  = goodsReceipts;
        this.audit          = audit;
    }

    /**
     * Resolve a target uid to its owning company id, per target type (ADR-0002 §2, ADR-0006 D-10,
     * ADR-0008 D-10, ADR-0011 D-10). Extended with purchases target types so that
     * {@code @perm.scoped(#uid,'purchaseorder','PURCHASE.ORDER.CREATE')} gates work correctly.
     */
    public Optional<Long> companyIdOf(String targetType, String uid) {
        if (targetType == null || uid == null) {
            return Optional.empty();
        }
        return switch (targetType.toLowerCase()) {
            case "company"        -> companies.findByUid(uid).map(c -> c.getId());
            case "branch"         -> branches.findByUid(uid).map(b -> b.getCompany().getId());
            case "customer"       -> customers.findCompanyIdByUid(uid);
            case "supplier"       -> suppliers.findCompanyIdByUid(uid);
            case "agent"          -> agents.findCompanyIdByUid(uid);
            case "otherparty"     -> otherParties.findCompanyIdByUid(uid);
            case "product"        -> products.findCompanyIdByUid(uid);
            case "pricelist"      -> priceLists.findCompanyIdByUid(uid);
            case "unit"           -> units.findCompanyIdByUid(uid);
            case "invoice"        -> salesInvoices.findCompanyIdByUid(uid);
            case "taxrate"        -> taxRates.findCompanyIdByUid(uid);
            case "stockonhand"    -> stockOnHands.findCompanyIdByUid(uid);
            case "stockmovement"  -> stockMovements.findCompanyIdByUid(uid);
            // Purchases target types (ADR-0011 D-10)
            case "purchaseorder"  -> purchaseOrders.findCompanyIdByUid(uid);
            case "goodsreceipt"   -> goodsReceipts.findCompanyIdByUid(uid);
            // organisation is global (root-only, not company-scoped); unknown types deny.
            default -> Optional.empty();
        };
    }

    /** True if the caller may act in {@code companyId}: root, or it is their active company. */
    public boolean canActIn(RequestContext.Principal principal, Long companyId) {
        if (principal == null || companyId == null) {
            return false;
        }
        return principal.root() || companyId.equals(principal.companyId());
    }

    /**
     * Whether the caller may act on the given target uid (root, or same active company). An
     * unresolvable target denies — never "allow because unknown".
     */
    public boolean canActOn(RequestContext.Principal principal, String targetType, String uid) {
        if (principal != null && principal.root()) {
            return true;
        }
        return companyIdOf(targetType, uid)
                .map(companyId -> canActIn(principal, companyId))
                .orElse(false);
    }

    /** Imperative form for the service layer (body-scoped ops): throw 403 if the caller can't act. */
    public void assertCanActIn(RequestContext.Principal principal, Long companyId) {
        if (!canActIn(principal, companyId)) {
            throw ForbiddenException.notPermitted();
        }
        // Audit the security-interesting bypass: root acting OUTSIDE its active company (a non-root
        // caller would have been denied here). Root acting within its own company is ordinary and is
        // already captured by the action's own audit row (ADR-0004 D-9). Called from @Transactional
        // service methods, so the MANDATORY audit write has a transaction to join.
        if (principal != null && principal.root() && companyId != null
                && !companyId.equals(principal.companyId())) {
            audit.record(AuditEvent.of(AuditActions.ROOT_BYPASS, "companies", companyId, null)
                    .detail(Map.of("activeCompanyId", String.valueOf(principal.companyId()),
                            "targetCompanyId", String.valueOf(companyId))));
        }
    }
}
