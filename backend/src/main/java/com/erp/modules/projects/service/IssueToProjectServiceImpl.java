package com.erp.modules.projects.service;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.projects.domain.dto.IssueToProjectRequest;
import com.erp.modules.projects.domain.dto.IssueToProjectResultDto;
import com.erp.modules.projects.domain.dto.IssueToProjectResultDto.IssueLineResultDto;
import com.erp.modules.projects.domain.dto.ProjectTag;
import com.erp.modules.projects.domain.dto.ProjectTagResolver;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryGlPoster.ProjectCogsLeg;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.Ulid;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue materials from stock to a project (ADR-0033 D-5, FR-PROJ-08).
 *
 * <p>Reuses the ADR-0020 COGS engine (costIssue + InventoryGlPoster.postCogsInNewTx).
 * The issue is a thin new path — no new engine, no new GL account (existing COGS/INVENTORY keys).
 * Movement type = ISSUE_TO_PROJECT (semantically distinct from ADJUSTMENT — D-5 decision).
 *
 * <p>The COGS GL post is in REQUIRES_NEW (InventoryGlPoster contract) so a GL-config failure
 * never rolls back the physical stock deduction (ADR-0020 D-4/FIX-B precedent).
 * Null avg-cost edge: skip the COGS leg, WARN + anomaly; qty still moves (ADR-0020 D-2 edge).
 */
@Service
@Transactional
public class IssueToProjectServiceImpl implements IssueToProjectService {

    private static final Logger log = LoggerFactory.getLogger(IssueToProjectServiceImpl.class);
    private static final String AUDIT_TARGET = "stock_movements";

    private final ProjectTagResolver       tagResolver;
    private final CompanyRepository        companies;
    private final BranchRepository         branches;
    private final ProductRepository        products;
    private final InventoryValuationService valuation;
    private final StockPostingService       stockPosting;
    private final InventoryGlPoster         glPoster;
    private final IssueNumberGenerator      numberGen;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;

    public IssueToProjectServiceImpl(ProjectTagResolver tagResolver,
                                     CompanyRepository companies,
                                     BranchRepository branches,
                                     ProductRepository products,
                                     InventoryValuationService valuation,
                                     StockPostingService stockPosting,
                                     InventoryGlPoster glPoster,
                                     IssueNumberGenerator numberGen,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.tagResolver  = tagResolver;
        this.companies    = companies;
        this.branches     = branches;
        this.products     = products;
        this.valuation    = valuation;
        this.stockPosting = stockPosting;
        this.glPoster     = glPoster;
        this.numberGen    = numberGen;
        this.scopeGuard   = scopeGuard;
        this.audit        = audit;
    }

    @Override
    public IssueToProjectResultDto issue(IssueToProjectRequest req,
                                         RequestContext.Principal principal) {
        // 1. Resolve company + branch
        var company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> new NotFoundException("Company not found: " + req.companyUid()));
        scopeGuard.assertCanActIn(principal, company.getId());

        var branch = branches.findByUid(req.branchUid())
                .orElseThrow(() -> new NotFoundException("Branch not found: " + req.branchUid()));

        // 2. Validate + resolve the project tag (BR-PROJ-01/04, OQ-PROJ-06)
        ProjectTag tag = tagResolver.resolve(company.getId(), req.projectUid(), req.projectTaskUid());

        // 3. Allocate issue number + uid (the sourceDocumentUid ties movements together)
        String issueNumber = numberGen.next(company.getId());
        String issueUid    = Ulid.next();

        LocalDate issueDate = req.issueDate() != null ? req.issueDate() : LocalDate.now();

        List<IssueLineResultDto> resultLines = new ArrayList<>();
        List<ProjectCogsLeg>     cogsLegs    = new ArrayList<>();
        BigDecimal               totalValue  = BigDecimal.ZERO;
        String                   currency    = company.getBaseCurrency();

        // 4. Per-line: costIssue + stockPost
        for (IssueToProjectRequest.IssueLine line : req.lines()) {
            Product product = products.findByUid(line.productUid())
                    .orElseThrow(() -> new NotFoundException("Product not found: " + line.productUid()));

            BigDecimal qty = line.qty().abs(); // always positive magnitude

            // costIssue returns null when avg_cost is not established (ADR-0020 D-2 edge)
            BigDecimal issuedValue = valuation.costIssue(
                    company.getId(), branch.getId(), product.getId(), qty);

            if (issuedValue == null) {
                log.warn("IssueToProject: null avg_cost for company={} product={} — "
                        + "COGS leg skipped; qty still deducted", company.getId(), line.productUid());
                issuedValue = BigDecimal.ZERO;
            }

            BigDecimal unitCost = issuedValue.compareTo(BigDecimal.ZERO) > 0
                    ? issuedValue.divide(qty, 4, java.math.RoundingMode.HALF_UP) : null;

            // Post stock movement (−qty, ISSUE_TO_PROJECT) with project dimension (ADR-0033 D-5)
            stockPosting.post(
                    company.getId(), branch.getId(), null /* default location */, product.getId(),
                    qty.negate(), MovementType.ISSUE_TO_PROJECT,
                    null, "PROJECT_ISSUE", issueUid,
                    null, req.reason() != null ? req.reason() : "Issue to project " + req.projectUid(),
                    Instant.now(), principal.userId(),
                    unitCost, issuedValue.compareTo(BigDecimal.ZERO) > 0 ? issuedValue : null,
                    null, null,
                    tag.projectId(), tag.projectTaskId()
            );

            if (issuedValue.compareTo(BigDecimal.ZERO) > 0) {
                cogsLegs.add(new ProjectCogsLeg(product.getId(), product.getCode(),
                        issuedValue, "MATERIAL"));
            }

            totalValue = totalValue.add(issuedValue);
            resultLines.add(new IssueLineResultDto(line.productUid(), qty, issuedValue));
        }

        // 5. Post DR COGS / CR INVENTORY — REQUIRES_NEW (InventoryGlPoster contract)
        //    Thread the project tag onto the COGS leg via the project-aware overload.
        String glEntryUid = null;
        if (!cogsLegs.isEmpty()) {
            glEntryUid = glPoster.postCogsForProjectInNewTx(
                    company.getId(), branch.getId(), issueDate, issueUid, issueNumber,
                    currency, tag.projectId(), tag.projectTaskId(), cogsLegs);
        }

        audit.record(AuditEvent.of(AuditActions.PROJECT_MATERIAL_ISSUE, AUDIT_TARGET,
                null, issueUid)
                .detail(Map.of(
                        "issueNumber",  issueNumber,
                        "projectUid",   req.projectUid(),
                        "totalValue",   totalValue.toPlainString(),
                        "lineCount",    String.valueOf(req.lines().size())
                )));

        return new IssueToProjectResultDto(
                issueUid, issueNumber, req.projectUid(),
                resultLines, glEntryUid, totalValue, currency);
    }
}
