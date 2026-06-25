package com.erp.platform.bootstrap;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.UserRole;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.repository.UserCompanyRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.modules.iam.service.UserCompanyService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time startup backfill for the {@code user_company} table (V77).
 *
 * <p>Guard: runs ONLY when {@code user_company} is empty ({@code count() == 0}). On every
 * subsequent boot the guard short-circuits immediately — the runner is a no-op once seeded.
 *
 * <p>Algorithm: union of all DISTINCT (user_id, company_id) pairs from active role grants
 * ({@code user_role.revoked_at IS NULL}) and ALL branch assignments ({@code user_branch},
 * deriving {@code company_id} via {@code branch.company}). For each pair call
 * {@link UserCompanyService#ensureMembership} (itself idempotent). Runs after
 * {@link BootstrapRunner} (Order 20 vs 10) so the org/company/root-user rows are guaranteed
 * present before we attempt to back-fill.
 */
@Component
@Order(20)
public class UserCompanyBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserCompanyBackfill.class);

    private final UserCompanyRepository userCompanyRepository;
    private final UserRoleRepository    userRoleRepository;
    private final UserBranchRepository  userBranchRepository;
    private final AppUserRepository     appUserRepository;
    private final UserCompanyService    userCompanyService;

    public UserCompanyBackfill(UserCompanyRepository userCompanyRepository,
                               UserRoleRepository    userRoleRepository,
                               UserBranchRepository  userBranchRepository,
                               AppUserRepository     appUserRepository,
                               UserCompanyService    userCompanyService) {
        this.userCompanyRepository = userCompanyRepository;
        this.userRoleRepository    = userRoleRepository;
        this.userBranchRepository  = userBranchRepository;
        this.appUserRepository     = appUserRepository;
        this.userCompanyService    = userCompanyService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userCompanyRepository.count() > 0) {
            log.debug("UserCompanyBackfill: table not empty — skipping.");
            return;
        }

        log.info("UserCompanyBackfill: user_company is empty — deriving memberships from role grants and branch assignments.");

        // Collect distinct (userId, companyId) pairs.
        // Use a simple record as the key so Set deduplication works correctly.
        record Pair(Long userId, Long companyId) {}
        Set<Pair> pairs = new LinkedHashSet<>();

        // 1) Active role grants → (user_id, company_id)
        List<UserRole> activeRoles = userRoleRepository.findAll().stream()
                .filter(ur -> ur.getRevokedAt() == null)
                .toList();
        for (UserRole ur : activeRoles) {
            pairs.add(new Pair(ur.getUserId(), ur.getCompanyId()));
        }

        // 2) ALL branch assignments → (user_id, branch.company_id)
        //    UserBranch has no revoked_at gate at the DB level (uses 'active' boolean instead),
        //    so we walk all and skip if company resolution fails.
        List<AppUser> allUsers = appUserRepository.findAll();
        for (AppUser u : allUsers) {
            userBranchRepository.findByUserIdOrderByAssignedAtAscIdAsc(u.getId())
                    .forEach(ub -> {
                        Branch branch = ub.getBranch();
                        if (branch != null && branch.getCompany() != null) {
                            pairs.add(new Pair(u.getId(), branch.getCompany().getId()));
                        }
                    });
        }

        int count = 0;
        for (Pair p : pairs) {
            userCompanyService.ensureMembership(p.userId(), p.companyId(), null);
            count++;
        }

        log.info("UserCompanyBackfill: seeded {} user_company rows from {} (user, company) pairs.",
                count, pairs.size());
    }
}
