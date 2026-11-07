package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * R-3(a) — the build-time compensating control for the rejected row-level security (D-4,
 * 2026-10-31): <b>every query method on a repository whose entity is tenant-scoped must carry a
 * company or organisation predicate</b>, or be named, with a reason, in {@link #ALLOWED}.
 *
 * <h2>What is checked</h2>
 *
 * Every Spring Data repository under {@code com.erp} whose entity (or a mapped superclass) has a
 * {@code companyId}/{@code company}/{@code organisationId}/{@code organisation} attribute. For each
 * method DECLARED on the repository (inherited CRUD is covered by {@link TenantScopingRulesTest}):
 * <ul>
 *   <li>a derived query passes if its name contains {@code CompanyId}, {@code Company_Id},
 *       {@code OrganisationId} or {@code Organisation_Id};
 *   <li>a {@link Query} passes if its text references {@code companyId}, {@code company.id},
 *       {@code company_id} or the organisation equivalents;
 *   <li>a method named {@code findScopedById} passes: its {@code @Query} reads the caller's tenant
 *       from the {@code RequestContext} (see {@code CompanyRepository.findScopedById}).
 * </ul>
 *
 * <h2>The allowlist</h2>
 *
 * Plain reflection, not the ArchUnit freeze store: freeze results differ between local and CI
 * (lambda attribution) and regenerating one locally breaks CI. Each exception is one
 * {@code "Repository#method"} entry with a reason. The test also fails on a STALE entry (method
 * deleted or now scoped), so the list cannot rot into a blanket pass.
 *
 * <p>Adding an entry is a security review decision: say why the caller cannot reach another
 * tenant's row through this method. The usual reasons are (1) uid/id lookup whose loaded entity is
 * scope-checked by the service ("scope from the LOADED entity, never a caller param"), (2) the
 * argument is a parent/key already resolved inside the caller's tenant, (3) the method is
 * deliberately cross-tenant platform work (reconciler, provisioning, background jobs).
 *
 * <p>Granularity is per method NAME: overloads share one entry.
 */
class TenantScopedRepositoryRulesTest {

    private static final Set<String> TENANT_ATTRIBUTES =
            Set.of("companyId", "company", "organisationId", "organisation");

    private static final Pattern DERIVED_SCOPE =
            Pattern.compile("(Company_?Id|Organisation_?Id)");

    private static final Pattern QUERY_SCOPE = Pattern.compile(
            "(?i)(company_?id|company\\.id|organisation_?id|organisation\\.id)");

    /** Entry format {@code Repository#method}; value is the one-line reason. */
    private static final Map<String, String> ALLOWED = new TreeMap<>();

    private static void a(String method, String reason) {
        ALLOWED.put(method, reason);
    }

    static {
        //ALLOWLIST-BEGIN
        a("ActivityRepository#findByLeadIdOrderByOccurredAtDesc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ActivityRepository#findByOpportunityIdOrderByOccurredAtDesc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ActivityRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("AgentAddressRepository#findByAgentId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("AgentAddressRepository#findByAgentIdAndAddressRole", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("AgentAddressRepository#findByAgentIdAndAddressRoleAndIsDefaultTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("AgentAddressRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("AgentContactRepository#existsByAgentIdAndIsPrimaryTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("AgentContactRepository#findByAgentId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("AgentContactRepository#findByAgentIdAndIsPrimaryTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("AgentContactRepository#findByAgentIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("AgentContactRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("AgentRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ApDebitNoteAllocationRepository#deleteByDebitNoteId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApDebitNoteAllocationRepository#findByDebitNoteId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApDebitNoteAllocationRepository#findBySupplierBillId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApDebitNoteAllocationRepository#sumAllocatedByDebitNote", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApDebitNoteRepository#existsBySupplierBillId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApDebitNoteRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ApPaymentAllocationRepository#findByApPaymentId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApPaymentAllocationRepository#findBySupplierBillId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApPaymentRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ApPaymentRepository#updateAmount", "UPDATE by id of a payment the calling service already loaded and scope-checked");
        a("AppUserRepository#existsByIdAndStatus", "id is an authenticated principal or already-resolved user id, not request input");
        a("AppUserRepository#existsByIdAndStatusAndRootFalse", "id is an authenticated principal or already-resolved user id, not request input");
        a("AppUserRepository#existsByUsername", "usernames are globally unique (D-7); uniqueness check must be estate-wide");
        a("AppUserRepository#findAllByOrderByUsername", "only reached when the caller has no organisation (data-gap fallback in UserServiceImpl); scoped variants used otherwise");
        a("AppUserRepository#findByRootFalseOrderByUsername", "only reached when the caller has no organisation (data-gap fallback in UserServiceImpl); scoped variants used otherwise");
        a("AppUserRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("AppUserRepository#findByUsername", "login/step-up identify by globally-unique username; org-alias suffix (D-7) disambiguates, no tenant known yet");
        a("ApprovalDecisionRepository#findByApprovalRequestIdOrderByDecidedAt", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApprovalDecisionRepository#findByApprovalRequestStepIdOrderByDecidedAt", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApprovalDecisionRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ApprovalPolicyRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ApprovalPolicyStepRepository#deleteByApprovalPolicyId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApprovalPolicyStepRepository#findByApprovalPolicyIdOrderBySequence", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApprovalPolicyStepRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ApprovalRequestRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ApprovalRequestStepRepository#findByApprovalRequestIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApprovalRequestStepRepository#findByApprovalRequestIdOrderBySequence", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApprovalRequestStepRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ApprovalRequestStepRepository#findPendingStepsForRole", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApprovalRequestStepRepository#findPendingStepsOrdered", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ApprovalRequestStepRepository#updatePendingToSkipped", "UPDATE keyed on a request id the calling service already loaded and scope-checked");
        a("ArCreditNoteAllocationRepository#deleteByCreditNoteId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ArCreditNoteAllocationRepository#findByArInvoiceId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ArCreditNoteAllocationRepository#findByCreditNoteId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ArCreditNoteAllocationRepository#sumAllocatedByCreditNote", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ArCreditNoteRepository#findByArInvoiceId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ArCreditNoteRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ArInvoiceRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ArInvoiceRepository#patchForFxTest", "test-only fixture patch keyed on an id the test created");
        a("ArReceiptAllocationRepository#deleteByReceiptId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ArReceiptAllocationRepository#findByArInvoiceId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ArReceiptAllocationRepository#findByReceiptId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ArReceiptAllocationRepository#sumAllocatedByReceipt", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ArReceiptRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ArWriteOffRepository#findByArInvoiceId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ArWriteOffRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("AssetCategoryRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("AssetDisposalRepository#findByFixedAssetId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("AssetDisposalRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("AssetRevaluationRepository#findByFixedAssetIdOrderByRevaluationDateAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("AssetRevaluationRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("AuditRepository#lockItem", "advisory-lock helper keyed on a caller-built string, touches no tenant rows");
        a("BankReconciliationRepository#findByCashBankAccountId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BankReconciliationRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BankReconciliationRepository#findFirstByCashBankAccountIdAndStatusOrderByStatementDateDescIdDesc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BarcodeSymbologyRuleRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BillMatchRepository#countComparedLinesByBillIds", "keyed on bill ids already resolved in the caller company");
        a("BillMatchRepository#findBySupplierBillId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BillMatchRepository#findBySupplierBillLineId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BlanketOrderLineRepository#findByBlanketOrderId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BlanketOrderLineRepository#findByBlanketOrderIdAndProductId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BlanketOrderLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BlanketOrderRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BomComponentRepository#deleteByBomId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BomComponentRepository#findByBomIdAndComponentProductId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BomComponentRepository#findByBomIdIn", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BomComponentRepository#findByBomIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BomComponentRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BomComponentRepository#findByUidAndBomId", "uid lookup under a parent id already resolved in the caller tenant");
        a("BomComponentRepository#maxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BomRepository#existsByParentProductIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BomRepository#findByParentProductIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BomRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BomRepository#maxVersionNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BranchCurrencyRepository#countByBranchId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BranchCurrencyRepository#existsByBranchIdAndCurrencyCode", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BranchCurrencyRepository#findByBranchIdAndActiveTrueOrderByCurrencyCode", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BranchCurrencyRepository#findByBranchIdAndCurrencyCode", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BranchCurrencyRepository#findByBranchIdAndCurrencyCodeAndActiveTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BranchCurrencyRepository#findByBranchIdAndIsDefaultTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BranchCurrencyRepository#findByBranchIdOrderByCurrencyCode", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BranchCurrencyRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BranchRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BranchRepository#findWithCompanyByUid", "uid lookup; service scope-checks the loaded branch (tenant-isolation model)");
        a("BudgetLineRepository#deleteByBudgetVersionId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BudgetLineRepository#existsByBudgetVersionId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BudgetLineRepository#findByBudgetVersionIdAndAccountIdAndFiscalPeriodId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BudgetLineRepository#findByBudgetVersionIdOrderByFiscalPeriodId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BudgetLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BudgetLineRepository#findByVersionAndPeriods", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BudgetLineRepository#sumAmountByVersionAccountPeriods", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BudgetRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BudgetVersionRepository#findByBudgetId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BudgetVersionRepository#findByBudgetIdOrderByVersionNoDesc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("BudgetVersionRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("BudgetVersionRepository#findMaxVersionNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CashBankAccountRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("CashBankAccountRepository#hasTransactions", "boolean probe keyed on an account id already resolved in the caller company");
        a("CashCountRepository#existsByCashAccountIdAndBusinessDateAndStatusIn", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CashCountRepository#findByCashAccountIdOrderByBusinessDateDescIdDesc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CashCountRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("CashTransactionRepository#bookBalance", "balance keyed on a cash account id already resolved in the caller company");
        a("CashTransactionRepository#bookBalanceAsOf", "balance keyed on a cash account id already resolved in the caller company");
        a("CashTransactionRepository#bookBalanceWrittenBefore", "balance keyed on a cash account id already resolved in the caller company");
        a("CashTransactionRepository#clearedBookBalance", "balance keyed on a cash account id already resolved in the caller company");
        a("CashTransactionRepository#findByCashBankAccountIdAndClearedFalse", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CashTransactionRepository#findByCashBankAccountIdOrderByTxnDateAscIdAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CashTransactionRepository#findByClearedInReconciliationId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CashTransactionRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("CashTransactionRepository#findByUidIn", "uid batch lookup; caller scope-checks every loaded row");
        a("CashTransferRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ChartOfAccountRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ChartOfAccountRepository#hasPostings", "boolean probe keyed on an account id already resolved in the caller company");
        a("ChequeRepository#findByCashBankAccountId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ChequeRepository#findByCashBankAccountIdAndChequeNumber", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ChequeRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("CompanyCurrencyRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("CompanyRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("CurrencyRateRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("CustomerAddressRepository#findByCustomerId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CustomerAddressRepository#findByCustomerIdAndAddressRole", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CustomerAddressRepository#findByCustomerIdAndAddressRoleAndIsDefaultTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CustomerAddressRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("CustomerContactRepository#existsByCustomerIdAndIsPrimaryTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CustomerContactRepository#findByCustomerId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CustomerContactRepository#findByCustomerIdAndIsPrimaryTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CustomerContactRepository#findByCustomerIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("CustomerContactRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("CustomerPriceRepository#findActiveForCustomerProduct", "keyed on customer and product ids already resolved in the caller company");
        a("CustomerPriceRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("CustomerRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("DeliveryLineRepository#findByDeliveryIdAndProductId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DeliveryLineRepository#findByDeliveryIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DeliveryLineRepository#findBySalesOrderLineId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DeliveryLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("DeliveryLineRepository#findByUidAndDeliveryId", "uid lookup under a parent id already resolved in the caller tenant");
        a("DeliveryLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DeliveryRepository#findBySalesOrderId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DeliveryRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("DeliveryRepository#findIdByUid", "uid to id resolution; the id is only used after the delivery is scope-checked");
        a("DepartmentRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("DepreciationRunLineRepository#findByDepreciationRunId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DepreciationRunRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("DepreciationScheduleLineRepository#findByFixedAssetIdOrderByScheduleVersionAscPeriodSeqAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DepreciationScheduleLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("DepreciationScheduleLineRepository#findCurrentVersionByAssetId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DepreciationScheduleLineRepository#findUnpostedUpToDate", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DimensionRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("DimensionValueRepository#existsByDimensionIdAndCode", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DimensionValueRepository#findByDimensionId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DimensionValueRepository#findByDimensionIdAndActiveTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DimensionValueRepository#findByParentId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("DimensionValueRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("DimensionValueRepository#hasPostings", "boolean probe keyed on a dimension value id already resolved in the caller company");
        a("DocumentBrandingRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("DocumentTemplateRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("DomainEventRepository#claimPendingForUpdate", "outbox dispatcher: platform job deliberately drains all tenants");
        a("DomainEventRepository#countByStatus", "outbox metrics, platform-wide, no row data");
        a("DomainEventRepository#existsByAggregateTypeAndAggregateIdAndEventTypeAndStatusIn", "outbox de-dup keyed on an aggregate id already resolved by the caller");
        a("DomainEventRepository#findPendingBatch", "outbox dispatcher: platform job deliberately drains all tenants");
        a("DomainEventRepository#oldestPendingOccurredAt", "outbox metrics, platform-wide, returns a timestamp only");
        a("EmployeeLoanInstallmentRepository#findByEmployeeLoanIdOrderByInstallmentNoAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("EmployeeLoanInstallmentRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("EmployeeLoanInstallmentRepository#findUnpaidByLoanId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("EmployeeLoanRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("EmployeeNextOfKinRepository#existsByEmployeeIdAndIsPrimaryTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("EmployeeNextOfKinRepository#findByEmployeeId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("EmployeeNextOfKinRepository#findByEmployeeIdAndIsPrimaryTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("EmployeeNextOfKinRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("EmployeeRecurringItemRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("EmployeeRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("EmploymentContractRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("FiscalPeriodRepository#findByFiscalYearIdOrderByPeriodNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("FiscalPeriodRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("FiscalReceiptRepository#findBySalesInvoiceId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("FiscalReceiptRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("FiscalYearRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("FixedAssetRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("FxRevaluationRunLineRepository#findByFxRevaluationRunId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("FxRevaluationRunRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("GeneratedDocumentRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("GlConfigRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("GoodsReceiptLineRepository#findByGoodsReceiptIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("GoodsReceiptLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("GoodsReceiptLineRepository#findByUidAndGoodsReceiptId", "uid lookup under a parent id already resolved in the caller tenant");
        a("GoodsReceiptLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("GoodsReceiptLineRepository#findNonVoidByPurchaseOrderLineId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("GoodsReceiptLineSerialRepository#deleteByGoodsReceiptLineId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("GoodsReceiptLineSerialRepository#findByGoodsReceiptLineId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("GoodsReceiptRepository#findByPurchaseOrderId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("GoodsReceiptRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("JournalBatchRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("JournalEntryRepository#existsByReversalOfId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("JournalEntryRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("JournalLineRepository#findByEntryIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("LandedCostAllocationRepository#existsByLandedCostIdAndGoodsReceiptLineId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("LandedCostAllocationRepository#findByLandedCostId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("LandedCostChargeRepository#findByLandedCostIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("LandedCostChargeRepository#findByUidAndLandedCostId", "uid lookup under a parent id already resolved in the caller tenant");
        a("LandedCostChargeRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("LandedCostReceiptRepository#existsByLandedCostIdAndGoodsReceiptId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("LandedCostReceiptRepository#findByLandedCostId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("LandedCostReceiptRepository#findByLandedCostIdAndGoodsReceiptId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("LandedCostRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("LeadRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("LeaveBalanceRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("LeaveRequestRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("LeaveTypeRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("NotificationDeliveryRepository#findByNotificationId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("NotificationDeliveryRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("NotificationPreferenceRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("NotificationRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("NotificationTypeRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("OpportunityLineRepository#countByOpportunityId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("OpportunityLineRepository#findByOpportunityIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("OpportunityLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("OpportunityLineRepository#findByUidAndOpportunityId", "uid lookup under a parent id already resolved in the caller tenant");
        a("OpportunityLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("OpportunityRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("OtherPartyAddressRepository#findByOtherPartyId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("OtherPartyAddressRepository#findByOtherPartyIdAndAddressRole", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("OtherPartyAddressRepository#findByOtherPartyIdAndAddressRoleAndIsDefaultTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("OtherPartyAddressRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("OtherPartyContactRepository#existsByOtherPartyIdAndIsPrimaryTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("OtherPartyContactRepository#findByOtherPartyId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("OtherPartyContactRepository#findByOtherPartyIdAndIsPrimaryTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("OtherPartyContactRepository#findByOtherPartyIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("OtherPartyContactRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("OtherPartyRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PayComponentRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PayeBandRepository#findByPayeBandSetIdOrderByBandNoAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PayeBandRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PayeBandSetRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PaymentRunRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PaymentTermsRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PayrollLineItemRepository#findByPayrollLineIdOrderByItemKindAscLabelAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PayrollLineItemRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PayrollLineRepository#findByPayrollRunIdAndEmployeeId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PayrollLineRepository#findByPayrollRunIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PayrollLineRepository#findByPayrollRunIdOrderByEmployeeIdAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PayrollLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PayrollRunRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PayrollStatutorySnapshotRepository#findByPayrollLineId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PayrollStatutorySnapshotRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PayslipRepository#findByPayrollRunId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PayslipRepository#findByPayrollRunIdAndEmployeeId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PayslipRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PettyCashFundRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PettyCashTransactionRepository#findByPettyCashFundIdOrderByTxnDateDescIdDesc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PettyCashTransactionRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PipelineStageRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PosSessionPayoutRepository#findByPosSessionIdOrderByCreatedAtAscIdAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PosSessionPayoutRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PosSessionPayoutRepository#payoutBreakdownForSession", "keyed on a POS session id already resolved in the caller company and branch");
        a("PosSessionPayoutRepository#totalPayoutsForSession", "keyed on a POS session id already resolved in the caller company and branch");
        a("PosSessionRepository#findByPosTillIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PosSessionRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PosTillRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PositionRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PrepStationRepository#existsByBranchIdAndNameIgnoreCase", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PrepStationRepository#existsByBranchIdAndNameIgnoreCaseAndIdNot", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PriceListRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PriceTierRepository#existsDuplicateBreak", "boolean probe keyed on product and price-list ids already resolved in the caller company");
        a("PriceTierRepository#findBestTier", "keyed on product and price-list ids already resolved in the caller company");
        a("PriceTierRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ProductBarcodeRepository#findByProductId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ProductBarcodeRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ProductBarcodeRepository#findByUidAndProductId", "uid lookup under a parent id already resolved in the caller tenant");
        a("ProductPriceRepository#existsByProductId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ProductPriceRepository#findByProductId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ProductPriceRepository#findByProductIdAndPriceListIdAndUnitId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ProductPriceRepository#findByProductIdAndPriceListIdAndUnitIdIsNull", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ProductPriceRepository#findFirstByProductIdAndUnitIdIsNullOrderByIdAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ProductPriceRepository#findFirstByProductIdAndUnitIdOrderByIdAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ProductRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ProjectRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ProjectTaskRepository#findByProjectIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ProjectTaskRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("ProjectTaskRepository#findByUidAndProjectId", "uid lookup under a parent id already resolved in the caller tenant");
        a("ProjectTimesheetRepository#findByProjectId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("ProjectTimesheetRepository#findByProjectIdAndProjectTaskId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PromotionRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PromotionUsageRepository#countByPromotionId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PromotionUsageRepository#findByPromotionId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PromotionUsageRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PurchaseOrderLineRepository#findByPurchaseOrderIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PurchaseOrderLineRepository#findByUidAndPurchaseOrderId", "uid lookup under a parent id already resolved in the caller tenant");
        a("PurchaseOrderLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PurchaseOrderRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PurchaseRequisitionLineRepository#findByPurchaseRequisitionIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PurchaseRequisitionLineRepository#findByUidAndPurchaseRequisitionId", "uid lookup under a parent id already resolved in the caller tenant");
        a("PurchaseRequisitionLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PurchaseRequisitionRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("PurchaseReturnLineRepository#findByPurchaseReturnIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PurchaseReturnLineRepository#findByUidAndPurchaseReturnId", "uid lookup under a parent id already resolved in the caller tenant");
        a("PurchaseReturnLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PurchaseReturnLineRepository#sumReturnedQtyInBaseForGrLine", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PurchaseReturnRepository#findByGoodsReceiptId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("PurchaseReturnRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("QuotationLineRepository#findByQuotationIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("QuotationLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("QuotationLineRepository#findByUidAndQuotationId", "uid lookup under a parent id already resolved in the caller tenant");
        a("QuotationLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("QuotationRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("RestaurantOrderLineRepository#findByRestaurantOrderIdAndUid", "uid lookup under a parent id already resolved in the caller tenant");
        a("RestaurantOrderLineRepository#findByRestaurantOrderIdInOrderByLineNoAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RestaurantOrderLineRepository#findByRestaurantOrderIdOrderByLineNoAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RestaurantOrderLineRepository#findMaxFireRound", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RestaurantOrderLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RestaurantOrderRepository#existsBySalesInvoiceIdAndIdNot", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RestaurantTableRepository#existsByBranchIdAndNameIgnoreCase", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RestaurantTableRepository#existsByBranchIdAndNameIgnoreCaseAndIdNot", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RfqLineRepository#findByRfqIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RfqLineRepository#findByUidAndRfqId", "uid lookup under a parent id already resolved in the caller tenant");
        a("RfqLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RfqRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("RfqSupplierRepository#existsByRfqIdAndSupplierId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RfqSupplierRepository#findByRfqId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RfqSupplierRepository#findByRfqIdAndSupplierId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("RoleRepository#existsByCode", "global-role code uniqueness check (uq_role_code_global); tenant roles use the scoped variant");
        a("RoleRepository#findAllByOrderByName", "unscoped list retained for system/bootstrap callers; RoleServiceImpl.list uses findVisibleTo");
        a("RoleRepository#findByCode", "resolves shipped GLOBAL bundles (ORG_ADMIN etc.) by code, organisation_id IS NULL by construction");
        a("RoleRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("RoleRepository#findWithPermissionsByUid", "uid lookup; UserRoleServiceImpl scope-checks the loaded role (tenant-isolation model)");
        a("RouteRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SalesInvoiceLineRepository#countByInvoiceId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SalesInvoiceLineRepository#findByInvoiceIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SalesInvoiceLineRepository#findByUidAndInvoiceId", "uid lookup under a parent id already resolved in the caller tenant");
        a("SalesInvoiceLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SalesInvoicePaymentRepository#findByInvoiceId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SalesInvoicePaymentRepository#findByUidAndInvoiceId", "uid lookup under a parent id already resolved in the caller tenant");
        a("SalesInvoicePaymentRepository#sumByPosSessionGroupedByTender", "keyed on a POS session id already resolved in the caller company and branch");
        a("SalesInvoicePaymentRepository#sumCashTenderByPosSession", "keyed on a POS session id already resolved in the caller company and branch");
        a("SalesInvoiceRepository#countByPosSession", "keyed on a POS session id already resolved in the caller company and branch");
        a("SalesInvoiceRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SalesInvoiceRepository#sumGrossByPosSession", "keyed on a POS session id already resolved in the caller company and branch");
        a("SalesOrderLineRepository#findBySalesOrderIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SalesOrderLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SalesOrderLineRepository#findByUidAndSalesOrderId", "uid lookup under a parent id already resolved in the caller tenant");
        a("SalesOrderLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SalesOrderRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SalesReturnLineRepository#findBySalesReturnIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SalesReturnRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StandingOrderLineRepository#findByStandingOrderId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("StandingOrderLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StandingOrderRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StandingOrderRepository#findDueForGeneration", "scheduled generation job deliberately runs across all tenants");
        a("StatutoryRateSetRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StockBatchRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StockCountLineRepository#findByStockCountIdOrderByLineNoAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("StockCountLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StockCountRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StockLocationRepository#existsByAgentIdAndStatusAndIdNot", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("StockLocationRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StockLocationRepository#hasNonZeroOnHand", "boolean probe keyed on a location id already resolved in the caller company");
        a("StockMovementRepository#existsBySourceEventUidAndProductId", "idempotency probe on an outbox event uid (ULID) produced by the same tenant write");
        a("StockMovementRepository#findBySourceDocumentUidAndMovementType", "outbox handler: source document uid comes from an event the same tenant emitted");
        a("StockMovementRepository#findBySourceEventUid", "idempotency lookup on an outbox event uid (ULID) produced by the same tenant write");
        a("StockMovementRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StockMovementRepository#findIssuedMovements", "source document uid taken from an already-loaded, scope-checked document");
        a("StockOnHandRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StockSerialRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("StockTransferLineRepository#findByStockTransferIdOrderByLineNoAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("StockTransferRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SupplierAddressRepository#findBySupplierId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierAddressRepository#findBySupplierIdAndAddressRole", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierAddressRepository#findBySupplierIdAndAddressRoleAndIsDefaultTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierAddressRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SupplierBankAccountRepository#existsBySupplierIdAndIsDefaultTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierBankAccountRepository#findBySupplierId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierBankAccountRepository#findBySupplierIdAndIsDefaultTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierBankAccountRepository#findBySupplierIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierBankAccountRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SupplierBillLineRepository#findBySupplierBillIdAndUid", "uid lookup under a parent id already resolved in the caller tenant");
        a("SupplierBillLineRepository#findBySupplierBillIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierBillLineRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SupplierBillLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierBillRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SupplierContactRepository#existsBySupplierIdAndIsPrimaryTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierContactRepository#findBySupplierId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierContactRepository#findBySupplierIdAndIsPrimaryTrue", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierContactRepository#findBySupplierIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierContactRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SupplierQuoteLineRepository#findBySupplierQuoteIdOrderByLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierQuoteLineRepository#findByUidAndSupplierQuoteId", "uid lookup under a parent id already resolved in the caller tenant");
        a("SupplierQuoteLineRepository#findMaxLineNo", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierQuoteRepository#findByRfqId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierQuoteRepository#findByRfqIdAndStatus", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("SupplierQuoteRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("SupplierRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("TaxRateRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("UnitOfMeasureRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("UserCompanyRepository#count", "platform-wide count used by bootstrap/backfill only");
        a("UserCompanyRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("UserCompanyRepository#findByUserIdAndRevokedAtIsNullOrderByAssignedAtAscIdAsc", "keyed on a user id resolved from the authenticated principal or a loaded user");
        a("UserRoleRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("UserRoleRepository#findByUserIdAndRevokedAtIsNull", "keyed on a user id resolved from the authenticated principal or a loaded user");
        a("UserRoleRepository#findUserIdsByRoleId", "PermissionResolver cache invalidation by role id; returns ids only, never exposed");
        a("VanReconciliationRepository#existsByVanLocationIdAndBusinessDateAndStatusNot", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("VanReconciliationRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("VatAdjustmentRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("VatAdjustmentRepository#findByVatReturnId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("VatReturnBandRepository#deleteByVatReturnId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("VatReturnBandRepository#findByVatReturnId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("VatReturnRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("WhtTransactionRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("WhtTypeRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("WorkOrderComponentRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("WorkOrderComponentRepository#findByWorkOrderIdAndUid", "uid lookup under a parent id already resolved in the caller tenant");
        a("WorkOrderComponentRepository#findByWorkOrderIdOrderByLineNoAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("WorkOrderOperationRepository#findAppliedByWorkOrderId", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("WorkOrderOperationRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        a("WorkOrderOperationRepository#findByWorkOrderIdAndUid", "uid lookup under a parent id already resolved in the caller tenant");
        a("WorkOrderOperationRepository#findByWorkOrderIdOrderBySeqNoAsc", "child/detail rows keyed on a parent or account id already resolved and scope-checked in the caller tenant");
        a("WorkOrderRepository#findByUid", "uid lookup; service scope-checks the loaded entity (tenant-isolation model), swept by TenantIsolationSweepIT");
        //ALLOWLIST-END
    }

    @Test
    void everyQueryOnATenantScopedRepositoryCarriesACompanyOrOrganisationPredicate() throws Exception {
        Set<String> unscoped = new TreeSet<>();
        int repositories = 0;
        int methods = 0;
        for (Class<?> repo : tenantScopedRepositories()) {
            repositories++;
            for (Method m : repo.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isDefault() || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                methods++;
                if (!isScoped(m)) {
                    unscoped.add(repo.getSimpleName() + "#" + m.getName());
                }
            }
        }
        assertThat(repositories).as("repositories discovered — the scan found nothing").isGreaterThan(50);
        assertThat(methods).isGreaterThan(200);

        List<String> newlyUnscoped = new ArrayList<>(unscoped);
        newlyUnscoped.removeAll(ALLOWED.keySet());
        assertThat(newlyUnscoped)
                .as("query methods on a tenant-scoped table with no company/organisation predicate. "
                        + "Scope the query (…CompanyId…/…OrganisationId… in the name, or "
                        + "company_id/organisation_id in the @Query) or — after a security review — "
                        + "add 'Repository#method' to ALLOWED with the reason")
                .isEmpty();

        List<String> stale = new ArrayList<>(ALLOWED.keySet());
        stale.removeAll(unscoped);
        assertThat(stale)
                .as("ALLOWED entries that no longer match an unscoped method — remove them")
                .isEmpty();
    }

    private static boolean isScoped(Method m) {
        if (m.getName().equals("findScopedById")) {
            return true;
        }
        Query q = m.getAnnotation(Query.class);
        if (q != null) {
            return QUERY_SCOPE.matcher(q.value() + " " + q.countQuery()).find();
        }
        return DERIVED_SCOPE.matcher(m.getName()).find();
    }

    private static List<Class<?>> tenantScopedRepositories() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition d) {
                        return d.getMetadata().isInterface();
                    }
                };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.erp")) {
            Class<?> repo = Class.forName(bd.getBeanClassName());
            Class<?> entity = ResolvableType.forClass(repo).as(Repository.class).getGeneric(0).resolve();
            if (entity != null && hasTenantAttribute(entity)) {
                out.add(repo);
            }
        }
        return out;
    }

    private static boolean hasTenantAttribute(Class<?> entity) {
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var f : c.getDeclaredFields()) {
                if (TENANT_ATTRIBUTES.contains(f.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
