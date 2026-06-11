package com.erp.platform.security;

import com.erp.modules.ap.repository.ApDebitNoteRepository;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.cashbank.repository.BankReconciliationRepository;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.cashbank.repository.CashTransferRepository;
import com.erp.modules.cashbank.repository.ChequeRepository;
import com.erp.modules.tax.repository.VatAdjustmentRepository;
import com.erp.modules.tax.repository.VatReturnRepository;
import com.erp.modules.tax.repository.WhtTransactionRepository;
import com.erp.modules.tax.repository.WhtTypeRepository;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
import com.erp.modules.gl.repository.GlConfigRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
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
import com.erp.modules.routes.repository.RouteRepository;
import com.erp.modules.sales.repository.DeliveryRepository;
import com.erp.modules.sales.repository.QuotationRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.modules.sales.repository.SalesOrderRepository;
import com.erp.modules.sales.repository.SalesReturnRepository;
import com.erp.modules.sales.repository.TaxRateRepository;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
// documents (ADR-0023)
import com.erp.modules.documents.repository.DocumentBrandingRepository;
import com.erp.modules.documents.repository.DocumentTemplateRepository;
import com.erp.modules.documents.repository.GeneratedDocumentRepository;
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
 * GL repositories added per ADR-0013 D-10 ({@code account}/{@code fiscalperiod}/{@code journalentry}/{@code glconfig}).
 * AR repositories added per ADR-0014 D-12 ({@code arinvoice}/{@code arreceipt}).
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
    private final RouteRepository          routes;
    // GL repositories (ADR-0013 D-10)
    private final ChartOfAccountRepository glAccounts;
    private final FiscalPeriodRepository   fiscalPeriods;
    private final FiscalYearRepository     fiscalYears;
    private final JournalEntryRepository   journalEntries;
    private final GlConfigRepository       glConfigs;
    // AR repositories (ADR-0014 D-12)
    private final ArInvoiceRepository      arInvoices;
    private final ArReceiptRepository      arReceipts;
    // AP repositories (ADR-0015 D-12)
    private final SupplierBillRepository   supplierBills;
    private final ApPaymentRepository      apPayments;
    private final ApDebitNoteRepository    apDebitNotes;
    // Cash & Bank repositories (ADR-0016 D-12)
    private final CashBankAccountRepository  cashBankAccounts;
    private final CashTransactionRepository  cashTransactions;
    private final CashTransferRepository     cashTransfers;
    private final ChequeRepository           cheques;
    private final BankReconciliationRepository bankReconciliations;
    // VAT / WHT repositories (ADR-0017 D-11)
    private final VatReturnRepository        vatReturns;
    private final VatAdjustmentRepository    vatAdjustments;
    private final WhtTypeRepository          whtTypes;
    private final WhtTransactionRepository   whtTransactions;
    // Sales Orders repositories (ADR-0021 D-10)
    private final QuotationRepository        quotations;
    private final SalesOrderRepository       salesOrders;
    private final DeliveryRepository         deliveryRepo;
    // Sales Returns repositories (ADR-0021 D-11, Stage 2)
    private final SalesReturnRepository      salesReturns;
    // documents (ADR-0023)
    private final GeneratedDocumentRepository generatedDocuments;
    private final DocumentTemplateRepository  documentTemplates;
    private final DocumentBrandingRepository  documentBrandings;
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
                      RouteRepository routes,
                      ChartOfAccountRepository glAccounts,
                      FiscalPeriodRepository fiscalPeriods,
                      FiscalYearRepository fiscalYears,
                      JournalEntryRepository journalEntries,
                      GlConfigRepository glConfigs,
                      ArInvoiceRepository arInvoices,
                      ArReceiptRepository arReceipts,
                      SupplierBillRepository supplierBills,
                      ApPaymentRepository apPayments,
                      ApDebitNoteRepository apDebitNotes,
                      CashBankAccountRepository cashBankAccounts,
                      CashTransactionRepository cashTransactions,
                      CashTransferRepository cashTransfers,
                      ChequeRepository cheques,
                      BankReconciliationRepository bankReconciliations,
                      VatReturnRepository vatReturns,
                      VatAdjustmentRepository vatAdjustments,
                      WhtTypeRepository whtTypes,
                      WhtTransactionRepository whtTransactions,
                      QuotationRepository quotations,
                      SalesOrderRepository salesOrders,
                      DeliveryRepository deliveryRepo,
                      SalesReturnRepository salesReturns,
                      // documents (ADR-0023)
                      GeneratedDocumentRepository generatedDocuments,
                      DocumentTemplateRepository documentTemplates,
                      DocumentBrandingRepository documentBrandings,
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
        this.routes         = routes;
        this.glAccounts     = glAccounts;
        this.fiscalPeriods  = fiscalPeriods;
        this.fiscalYears    = fiscalYears;
        this.journalEntries = journalEntries;
        this.glConfigs      = glConfigs;
        this.arInvoices     = arInvoices;
        this.arReceipts     = arReceipts;
        this.supplierBills       = supplierBills;
        this.apPayments          = apPayments;
        this.apDebitNotes        = apDebitNotes;
        this.cashBankAccounts    = cashBankAccounts;
        this.cashTransactions    = cashTransactions;
        this.cashTransfers       = cashTransfers;
        this.cheques             = cheques;
        this.bankReconciliations = bankReconciliations;
        this.vatReturns          = vatReturns;
        this.vatAdjustments      = vatAdjustments;
        this.whtTypes            = whtTypes;
        this.whtTransactions     = whtTransactions;
        this.quotations          = quotations;
        this.salesOrders         = salesOrders;
        this.deliveryRepo        = deliveryRepo;
        this.salesReturns        = salesReturns;
        // documents (ADR-0023)
        this.generatedDocuments  = generatedDocuments;
        this.documentTemplates   = documentTemplates;
        this.documentBrandings   = documentBrandings;
        this.audit               = audit;
    }

    /**
     * Resolve a target uid to its owning company id, per target type (ADR-0002 §2, ADR-0006 D-10,
     * ADR-0008 D-10, ADR-0011 D-10, ADR-0014 D-12). Extended with AR target types so that
     * {@code @perm.scoped(#uid,'arinvoice','AR.INVOICE.VIEW')} and
     * {@code @perm.scoped(#uid,'arreceipt','AR.VIEW')} gates work correctly.
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
            // Routes target type (ADR-0012 D-9)
            case "route"          -> routes.findCompanyIdByUid(uid);
            // GL target types (ADR-0013 D-10 + ADR-0019 D-1)
            case "account"        -> glAccounts.findCompanyIdByUid(uid);
            case "fiscalperiod"   -> fiscalPeriods.findCompanyIdByUid(uid);
            case "fiscalyear"     -> fiscalYears.findCompanyIdByUid(uid);
            case "journalentry"   -> journalEntries.findCompanyIdByUid(uid);
            case "glconfig"       -> glConfigs.findCompanyIdByUid(uid);
            // AR target types (ADR-0014 D-12)
            case "arinvoice"      -> arInvoices.findCompanyIdByUid(uid);
            case "arreceipt"      -> arReceipts.findCompanyIdByUid(uid);
            // AP target types (ADR-0015 D-12)
            case "supplierbill"        -> supplierBills.findCompanyIdByUid(uid);
            case "appayment"           -> apPayments.findCompanyIdByUid(uid);
            case "apdebitnote"         -> apDebitNotes.findCompanyIdByUid(uid);
            // Cash & Bank target types (ADR-0016 D-12)
            case "cashbankaccount"     -> cashBankAccounts.findCompanyIdByUid(uid);
            case "cashtransaction"     -> cashTransactions.findCompanyIdByUid(uid);
            case "cashtransfer"        -> cashTransfers.findCompanyIdByUid(uid);
            case "cheque"              -> cheques.findCompanyIdByUid(uid);
            case "bankreconciliation"  -> bankReconciliations.findCompanyIdByUid(uid);
            // VAT / WHT target types (ADR-0017 D-11)
            case "vatreturn"           -> vatReturns.findCompanyIdByUid(uid);
            case "vatadjustment"       -> vatAdjustments.findCompanyIdByUid(uid);
            case "whttype"             -> whtTypes.findCompanyIdByUid(uid);
            case "whttransaction"      -> whtTransactions.findCompanyIdByUid(uid);
            // Sales Orders target types (ADR-0021 D-10)
            case "quotation"           -> quotations.findCompanyIdByUid(uid);
            case "salesorder"          -> salesOrders.findCompanyIdByUid(uid);
            case "delivery"            -> deliveryRepo.findCompanyIdByUid(uid);
            // Sales Returns target types (ADR-0021 D-11, Stage 2)
            case "salesreturn"         -> salesReturns.findCompanyIdByUid(uid);
            // documents (ADR-0023)
            case "generateddocument"   -> generatedDocuments.findCompanyIdByUid(uid);
            case "documenttemplate"    -> documentTemplates.findCompanyIdByUid(uid);
            case "documentbranding"    -> documentBrandings.findCompanyIdByUid(uid);
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
        // already captured by the action's own audit row (ADR-0004 D-9).
        // Use recordIndependent (REQUIRES_NEW): assertCanActIn is also called from
        // @Transactional(readOnly = true) query paths (AR ageing/statement/balance, GL trial
        // balance), where a MANDATORY INSERT fails "cannot execute INSERT in a read-only
        // transaction" — turning a root cross-company report view into a 500 (ISSUES-REGISTER #11).
        // The bypass audit is an independent security record and commits in its own transaction.
        if (principal != null && principal.root() && companyId != null
                && !companyId.equals(principal.companyId())) {
            audit.recordIndependent(AuditEvent.of(AuditActions.ROOT_BYPASS, "companies", companyId, null)
                    .detail(Map.of("activeCompanyId", String.valueOf(principal.companyId()),
                            "targetCompanyId", String.valueOf(companyId))));
        }
    }
}
