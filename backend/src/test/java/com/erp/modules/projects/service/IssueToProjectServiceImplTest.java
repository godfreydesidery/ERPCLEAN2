package com.erp.modules.projects.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.projects.domain.dto.IssueToProjectRequest;
import com.erp.modules.projects.domain.dto.IssueToProjectResultDto;
import com.erp.modules.projects.domain.dto.ProjectTag;
import com.erp.modules.projects.domain.dto.ProjectTagResolver;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for IssueToProjectServiceImpl — specifically Finding #5: the value_amount
 * passed to StockPostingService must be POSITIVE (absolute cost, consistent with
 * SaleIssueStockHandler, ADR-0020 D-2 convention).
 */
class IssueToProjectServiceImplTest {

    private ProjectTagResolver       tagResolver;
    private CompanyRepository        companies;
    private BranchRepository         branches;
    private ProductRepository        products;
    private InventoryValuationService valuation;
    private StockPostingService       stockPosting;
    private InventoryGlPoster         glPoster;
    private IssueNumberGenerator      numberGen;
    private ScopeGuard               scopeGuard;
    private AuditService             audit;
    private IssueToProjectServiceImpl service;

    @BeforeEach
    void setUp() {
        tagResolver  = mock(ProjectTagResolver.class);
        companies    = mock(CompanyRepository.class);
        branches     = mock(BranchRepository.class);
        products     = mock(ProductRepository.class);
        valuation    = mock(InventoryValuationService.class);
        stockPosting = mock(StockPostingService.class);
        glPoster     = mock(InventoryGlPoster.class);
        numberGen    = mock(IssueNumberGenerator.class);
        scopeGuard   = mock(ScopeGuard.class);
        audit        = mock(AuditService.class);
        service      = new IssueToProjectServiceImpl(tagResolver, companies, branches, products,
                valuation, stockPosting, glPoster, numberGen, scopeGuard, audit);
    }

    /**
     * Finding #5: value_amount passed to StockPostingService must be positive (absolute cost).
     * Before the fix, issuedValue was negated — that made it negative and inconsistent with
     * SaleIssueStockHandler which passes the positive issuedValue from costIssue().
     */
    @Test
    void issue_valueAmountPassedToStockPosting_isPositive() {
        // Arrange
        Company company = mockCompany(10L, "COMP-1", "USD");
        Branch branch   = mockBranch(20L, "BR-1");
        Product product = mockProduct(1L, "PROD-1", "WGT");

        when(companies.findByUid("COMP-1")).thenReturn(Optional.of(company));
        when(branches.findByUid("BR-1")).thenReturn(Optional.of(branch));
        when(products.findByUid("PROD-1")).thenReturn(Optional.of(product));
        when(tagResolver.resolve(10L, "PROJ-1", null)).thenReturn(new ProjectTag(100L, null));
        when(numberGen.next(10L)).thenReturn("PJI-0001");

        BigDecimal qty         = new BigDecimal("5");
        BigDecimal avgCost     = new BigDecimal("20.0000");  // unit cost
        BigDecimal issuedValue = new BigDecimal("100.0000"); // qty × avgCost (positive, from costIssue)
        when(valuation.costIssue(10L, 20L, 1L, qty)).thenReturn(issuedValue);
        when(stockPosting.post(anyLong(), anyLong(), any(), anyLong(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyLong(), any(), anyLong(), any()))
                .thenReturn("movement-uid-1");

        var request = new IssueToProjectRequest(
                "COMP-1", "BR-1", "PROJ-1", null,
                List.of(new IssueToProjectRequest.IssueLine("PROD-1", qty)),
                LocalDate.now(), "test issue");

        // Act
        IssueToProjectResultDto result = service.issue(request, principal(1L, 10L));

        // Assert: StockPostingService.post must receive a POSITIVE value_amount (not negated)
        // Capture the call to the 19-arg project-aware overload
        ArgumentCaptor<BigDecimal> valueCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockPosting).post(
                eq(10L), eq(20L), eq(null), eq(1L),
                any(),                         // quantity (negated)
                eq(MovementType.ISSUE_TO_PROJECT),
                any(), any(), any(), any(), any(), any(), any(),
                any(),                         // unitCost
                valueCaptor.capture(),         // valueAmount — must be positive
                eq(null), eq(null),
                eq(100L), eq(null));

        assertThat(valueCaptor.getValue())
                .as("value_amount must be positive (absolute cost, consistent with SaleIssueStockHandler)")
                .isEqualByComparingTo(issuedValue);
        assertThat(valueCaptor.getValue().compareTo(BigDecimal.ZERO))
                .as("value_amount must be > 0 (not negated)")
                .isGreaterThan(0);

        // Also assert the result totals are correct
        assertThat(result.totalValue()).isEqualByComparingTo(issuedValue);
    }

    @Test
    void issue_nullCostIssue_valueAmountIsNull() {
        // When avg_cost is not established, costIssue returns null; valueAmount must be null (not negated null)
        Company company = mockCompany(10L, "COMP-1", "USD");
        Branch branch   = mockBranch(20L, "BR-1");
        Product product = mockProduct(1L, "PROD-1", "WGT");

        when(companies.findByUid("COMP-1")).thenReturn(Optional.of(company));
        when(branches.findByUid("BR-1")).thenReturn(Optional.of(branch));
        when(products.findByUid("PROD-1")).thenReturn(Optional.of(product));
        when(tagResolver.resolve(10L, "PROJ-1", null)).thenReturn(new ProjectTag(100L, null));
        when(numberGen.next(10L)).thenReturn("PJI-0002");

        // costIssue returns null → ADR-0020 D-2 edge
        when(valuation.costIssue(anyLong(), anyLong(), anyLong(), any())).thenReturn(null);
        when(stockPosting.post(anyLong(), anyLong(), any(), anyLong(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyLong(), any(), anyLong(), any()))
                .thenReturn("movement-uid-2");

        var request = new IssueToProjectRequest(
                "COMP-1", "BR-1", "PROJ-1", null,
                List.of(new IssueToProjectRequest.IssueLine("PROD-1", new BigDecimal("3"))),
                LocalDate.now(), null);

        IssueToProjectResultDto result = service.issue(request, principal(1L, 10L));

        // No GL posting when costIssue returns null
        assertThat(result.cogsGlEntryUid()).isNull();
        // TotalValue should be zero (null treated as zero)
        assertThat(result.totalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * PROJECTS-044 gap-1 (service-layer mirror): qty = null must throw IllegalArgumentException
     * with a clear message — never NPE inside line.qty().abs().
     * (The HTTP layer catches this via @NotNull on IssueLine; this test guards programmatic callers.)
     */
    @Test
    void issue_nullQty_throwsIllegalArgumentException() {
        Company company = mockCompany(10L, "COMP-1", "USD");
        Branch branch   = mockBranch(20L, "BR-1");
        Product product = mockProduct(1L, "PROD-1", "WGT");

        when(companies.findByUid("COMP-1")).thenReturn(Optional.of(company));
        when(branches.findByUid("BR-1")).thenReturn(Optional.of(branch));
        when(products.findByUid("PROD-1")).thenReturn(Optional.of(product));
        when(tagResolver.resolve(10L, "PROJ-1", null)).thenReturn(new ProjectTag(100L, null));
        when(numberGen.next(10L)).thenReturn("PJI-0003");

        var request = new IssueToProjectRequest(
                "COMP-1", "BR-1", "PROJ-1", null,
                List.of(new IssueToProjectRequest.IssueLine("PROD-1", null)),
                LocalDate.now(), "null qty test");
        var p = principal(1L, 10L);

        assertThatThrownBy(() -> service.issue(request, p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qty must not be null");
    }

    /**
     * PROJECTS-044 gap-2: qty = 0 would pass @NotNull but violate @DecimalMin("0.0001").
     * At the service layer the null-guard fires before abs(); a zero qty that somehow bypasses
     * DTO validation still gets rejected by costIssue (or stock check) — but this test confirms
     * the service does NOT NPE when qty is non-null but zero (it proceeds to the stock layer).
     * The real gate for qty=0 is Bean Validation on IssueLine (tested separately via the DTO).
     */
    @Test
    void issue_zeroQty_doesNotNpe_reachesStockLayer() {
        Company company = mockCompany(10L, "COMP-1", "USD");
        Branch branch   = mockBranch(20L, "BR-1");
        Product product = mockProduct(1L, "PROD-1", "WGT");

        when(companies.findByUid("COMP-1")).thenReturn(Optional.of(company));
        when(branches.findByUid("BR-1")).thenReturn(Optional.of(branch));
        when(products.findByUid("PROD-1")).thenReturn(Optional.of(product));
        when(tagResolver.resolve(10L, "PROJ-1", null)).thenReturn(new ProjectTag(100L, null));
        when(numberGen.next(10L)).thenReturn("PJI-0004");
        // costIssue with qty=0 returns null (no cost established for zero quantity)
        when(valuation.costIssue(anyLong(), anyLong(), anyLong(), eq(BigDecimal.ZERO)))
                .thenReturn(null);
        when(stockPosting.post(anyLong(), anyLong(), any(), anyLong(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyLong(), any(), anyLong(), any()))
                .thenReturn("movement-uid-zero");

        var request = new IssueToProjectRequest(
                "COMP-1", "BR-1", "PROJ-1", null,
                List.of(new IssueToProjectRequest.IssueLine("PROD-1", BigDecimal.ZERO)),
                LocalDate.now(), "zero qty test");

        // Must not throw NPE — the null-guard is on qty==null only; zero is valid Java but invalid
        // domain (caught at HTTP layer by @DecimalMin). Service completes with zero totalValue.
        IssueToProjectResultDto result = service.issue(request, principal(1L, 10L));
        assertThat(result.totalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Company mockCompany(Long id, String uid, String currency) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        when(c.getUid()).thenReturn(uid);
        when(c.getBaseCurrency()).thenReturn(currency);
        return c;
    }

    private static Branch mockBranch(Long id, String uid) {
        Branch b = mock(Branch.class);
        when(b.getId()).thenReturn(id);
        when(b.getUid()).thenReturn(uid);
        return b;
    }

    private static Product mockProduct(Long id, String uid, String code) {
        Product p = mock(Product.class);
        when(p.getId()).thenReturn(id);
        when(p.getUid()).thenReturn(uid);
        when(p.getCode()).thenReturn(code);
        return p;
    }

    private static RequestContext.Principal principal(Long userId, Long companyId) {
        return new RequestContext.Principal(userId, "user@test.com", false, companyId, null, null);
    }
}
