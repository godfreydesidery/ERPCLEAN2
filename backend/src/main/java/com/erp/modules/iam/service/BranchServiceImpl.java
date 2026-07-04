package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.BranchDto;
import com.erp.modules.iam.domain.dto.CreateBranchRequest;
import com.erp.modules.iam.domain.dto.UpdateBranchRequest;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.cashbank.service.PettyCashFundSeeder;
import com.erp.modules.stock.service.StockLocationSeeder;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BranchServiceImpl implements BranchService {

    private final BranchRepository      branches;
    private final CompanyRepository     companies;
    private final UserBranchRepository  userBranches;
    private final StockLocationSeeder   stockLocationSeeder;
    // Petty cash default fund seeder (ADR-0050 D-7 PR-B): CompanyProvisioningServiceImpl's call is
    // a no-op for a brand-new company (no branch exists at that point — petty_cash_funds.branch_id
    // is NOT NULL), so this branch-scoped call completes the seed once a (default) branch exists,
    // mirroring the stockLocationSeeder wiring above.
    private final PettyCashFundSeeder   pettyCashFundSeeder;

    public BranchServiceImpl(BranchRepository branches, CompanyRepository companies,
                             UserBranchRepository userBranches,
                             StockLocationSeeder stockLocationSeeder,
                             PettyCashFundSeeder pettyCashFundSeeder) {
        this.branches            = branches;
        this.companies           = companies;
        this.userBranches        = userBranches;
        this.stockLocationSeeder = stockLocationSeeder;
        this.pettyCashFundSeeder = pettyCashFundSeeder;
    }

    @Override
    public BranchDto create(CreateBranchRequest request) {
        Company company = Lookups.orNotFound(
                companies.findByUid(request.companyUid()), "Company", request.companyUid());

        if (branches.existsByCompanyIdAndCode(company.getId(), request.code())) {
            throw new ConflictException("Branch code already exists in this company: " + request.code());
        }

        Branch branch = new Branch(company, request.code(), request.name());
        branch.setTimeZone(
                request.timeZone() != null && !request.timeZone().isBlank()
                        ? request.timeZone()
                        : company.getTimeZone());
        Branch saved = branches.save(branch);

        // Seed WAREHOUSE default + in-transit OTHER locations for every new branch (ADR-0028 D-4/D-5).
        // V37 only backfills branches existing at migration time; this covers post-migration branches.
        stockLocationSeeder.seedDefaults(company.getId(), saved.getId(), saved.getCode());

        if (request.makeDefault()) {
            applyDefault(saved);
        }

        // Complete the default petty-cash fund seed once a default branch exists (ADR-0050 D-7
        // PR-B) — a no-op if the company already has one, or if this branch isn't (yet) the default.
        pettyCashFundSeeder.seedDefaults(company.getId());

        return BranchDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public BranchDto getByUid(String uid) {
        return BranchDto.from(requireByUid(uid));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchDto> listByCompanyUid(String companyUid) {
        Company company = Lookups.orNotFound(
                companies.findByUid(companyUid), "Company", companyUid);
        return branches.findByCompanyIdOrderByName(company.getId()).stream()
                .map(BranchDto::from)
                .toList();
    }

    @Override
    public BranchDto updateByUid(String uid, UpdateBranchRequest request) {
        Branch branch = requireByUid(uid);
        branch.setName(request.name());
        if (request.timeZone() != null && !request.timeZone().isBlank()) {
            branch.setTimeZone(request.timeZone());
        }
        return BranchDto.from(branch); // dirty-checked within the TX
    }

    @Override
    public BranchDto setDefault(String uid) {
        Branch branch = applyDefault(requireByUid(uid));

        // Complete the default petty-cash fund seed for every path that establishes a company's
        // default branch (ADR-0050 D-7 PR-B) — not just branch-create-with-makeDefault. Covers the
        // gap where a company's first branch is created NON-default (seeder deferred, no branch was
        // default yet) and is only promoted to default afterwards via this method. Idempotent
        // (guarded by existsByCompanyIdAndCode), so it's harmless alongside the create-time call.
        pettyCashFundSeeder.seedDefaults(branch.getCompany().getId());

        return BranchDto.from(branch);
    }

    @Override
    public void archiveByUid(String uid) {
        Branch branch = requireByUid(uid);
        if (branch.isDefault()) {
            throw new ConflictException(
                    "Cannot archive the default branch; set another branch as default first.");
        }
        branch.setStatus(MasterStatus.ARCHIVED);

        // Any user whose DEFAULT is this branch must fall back, so a decommissioned branch can't
        // scope a future login (security review, Slice 4). Clear the default and promote that user's
        // earliest remaining assignment (D-D); if they have none, they're left with no active branch.
        for (UserBranch dflt : userBranches.findByBranchIdAndIsDefaultTrue(branch.getId())) {
            dflt.clearDefault();
            userBranches.saveAndFlush(dflt);
            userBranches
                    .findFirstByUserIdAndIdNotOrderByAssignedAtAscIdAsc(dflt.getUserId(), dflt.getId())
                    .ifPresent(UserBranch::markDefault);
        }
    }

    /**
     * The single home of the BR-2 invariant: clear the company's current default (if any and
     * different), then mark this branch the default. Runs inside the caller's transaction so the
     * partial unique index never sees two defaults. The clear is flushed before the set so the
     * unique index is satisfied at flush time.
     */
    private Branch applyDefault(Branch target) {
        Optional<Branch> current = branches.findByCompanyIdAndIsDefaultTrue(target.getCompany().getId());
        if (current.isPresent() && !current.get().getId().equals(target.getId())) {
            current.get().setDefault(false);
            branches.saveAndFlush(current.get());
        }
        target.setDefault(true);
        return branches.saveAndFlush(target);
    }

    private Branch requireByUid(String uid) {
        return Lookups.orNotFound(branches.findByUid(uid), "Branch", uid);
    }
}
