package com.erp.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.service.UserRoleService;
import com.erp.platform.security.CompanyTenantIndex;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Guards the harness seam in {@link PostgresIntegrationTest#dropIdKeyedCaches()}.
 *
 * <p><b>The trap this exists to prevent.</b> {@link PermissionResolver} and
 * {@link CompanyTenantIndex} are singletons in the ONE Spring context every IT shares, and both
 * memoise an answer under a numeric primary key. {@code IamTestData.clearAll()} resets the database
 * with {@code TRUNCATE ... RESTART IDENTITY}, which RE-ISSUES those keys from 1 in every test
 * method. So a memo written in one method can be served to a DIFFERENT row in the next — user 2's
 * permissions to a different user 2, company 1's tenant to a company 1 that now belongs to someone
 * else. Neither cache is wrong in production: identity sequences there are monotonic and never
 * re-issue an id, and {@code companies.organisation_id} is write-once.
 *
 * <p>It is not hypothetical. It is what made two security ITs fail in CI for twelve consecutive
 * runs while passing on a developer machine:
 * {@code TwoOrganisationIsolationIT.canActInRefusesAnotherTenantsCompany} (a stale
 * {@code CompanyTenantIndex} entry made ScopeGuard read "same tenant", so root was allowed into
 * another tenant's company) and
 * {@code SetPasswordAuthorityCeilingIT.setPassword_targetOutranksCaller_throwsForbidden} (a stale
 * {@link PermissionResolver} entry gave the caller authority it no longer had, so the ADR-0059
 * ceiling let the reset through). Both guards were correct; the inputs were poisoned.
 *
 * <p><b>Why the assertions look the way they do.</b> Both are timing-independent by construction,
 * because the original failures were NOT reproducible on a slow machine:
 * <ul>
 *   <li>{@code PermissionResolver} entries expire after 30 s. Its {@code resolve} takes the clock as
 *       a parameter, so the second method re-uses the FIRST method's timestamp — a surviving entry
 *       is then still live by that clock no matter how long the fixture took. Without the seam this
 *       assertion fails on every machine, not only a fast one.
 *   <li>{@code CompanyTenantIndex} has no TTL at all. The second method inserts a decoy organisation
 *       so the recycled company id lands under a DIFFERENT organisation than in the first, which a
 *       surviving memo cannot report.
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdKeyedCacheResetIT extends PostgresIntegrationTest {

    @Autowired private PermissionResolver     permissionResolver;
    @Autowired private CompanyTenantIndex     companyTenantIndex;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private RoleRepository         roleRepo;
    @Autowired private PermissionRepository   permissionRepo;
    @Autowired private UserRoleService        userRoleService;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private static final String PASSWORD = "CacheSeam1!";

    /** Carried from the first method to the second — the whole point is that ids repeat. */
    private static long   poisonNowMillis;
    private static Long   poisonedUserId;
    private static Long   poisonedCompanyId;
    private static Long   poisonedBranchId;
    private static Long   poisonedOrganisationId;

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @Order(1)
    void firstMethod_populatesBothCaches() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Cache Seam Org"));
        Company company  = companies.save(new Company(org, "CS", "Cache Seam Co"));
        Branch branch    = branches.save(new Branch(company, "CSB", "Cache Seam Branch"));

        AppUser root = new AppUser("seam_root", passwordEncoder.encode(PASSWORD), "Seam Root");
        root.setRoot(true);
        root = users.save(root);
        AppUser holder = users.save(
                new AppUser("seam_holder", passwordEncoder.encode(PASSWORD), "Seam Holder"));

        var glPost = permissionRepo.findByCode("GL.POST")
                .orElseThrow(() -> new IllegalStateException("GL.POST must be seeded"));
        Role glRole = new Role("SEAM_GL_ROLE", "Seam GL Role");
        glRole.setPermissions(Set.of(glPost));
        glRole = roleRepo.save(glRole);

        RequestContext.set(new RequestContext.Principal(
                root.getId(), "seam_root", true, company.getId(), branch.getId(), null));
        testData.seedMembership(holder.getUid(), company.getUid());
        userRoleService.grant(new GrantRoleRequest(
                holder.getUid(), glRole.getUid(), company.getUid(), null));

        poisonNowMillis        = System.currentTimeMillis();
        poisonedUserId         = holder.getId();
        poisonedCompanyId      = company.getId();
        poisonedBranchId       = branch.getId();
        poisonedOrganisationId = org.getId();

        assertThat(permissionResolver.resolve(
                poisonedUserId, poisonedCompanyId, poisonedBranchId, poisonNowMillis))
                .as("this method must actually WRITE a permission-cache entry, or the next "
                        + "method's assertion proves nothing")
                .contains("GL.POST");

        assertThat(companyTenantIndex.organisationOf(poisonedCompanyId))
                .as("this method must actually WRITE a tenant-index entry")
                .isEqualTo(poisonedOrganisationId);
    }

    @Test
    @Order(2)
    void secondMethod_seesNeitherPoisonedEntry() {
        testData.clearAll();

        // A decoy organisation so the RECYCLED company id lands under a different tenant than it
        // did in the first method — the discriminator a surviving memo cannot get right.
        organisations.save(new Organisation("Cache Seam Decoy Org"));
        Organisation org = organisations.save(new Organisation("Cache Seam Org II"));
        Company company  = companies.save(new Company(org, "CS", "Cache Seam Co II"));
        Branch branch    = branches.save(new Branch(company, "CSB", "Cache Seam Branch II"));

        AppUser root = new AppUser("seam_root", passwordEncoder.encode(PASSWORD), "Seam Root");
        root.setRoot(true);
        users.save(root);
        // Same creation order as the first method, so this user takes the recycled id — but with no
        // role granted to it this time.
        AppUser holder = users.save(
                new AppUser("seam_holder", passwordEncoder.encode(PASSWORD), "Seam Holder"));

        // Preconditions. If ids ever stop being recycled the trap is gone and so is the reason for
        // this test — fail loudly rather than pass for the wrong reason.
        assertThat(holder.getId())
                .as("TRUNCATE ... RESTART IDENTITY must have re-issued the same user id")
                .isEqualTo(poisonedUserId);
        assertThat(company.getId())
                .as("TRUNCATE ... RESTART IDENTITY must have re-issued the same company id")
                .isEqualTo(poisonedCompanyId);
        assertThat(branch.getId()).isEqualTo(poisonedBranchId);
        assertThat(org.getId())
                .as("the decoy must have pushed this company under a DIFFERENT organisation id")
                .isNotEqualTo(poisonedOrganisationId);

        // Deliberately re-using the FIRST method's clock: a surviving entry has not expired by it.
        assertThat(permissionResolver.resolve(
                poisonedUserId, poisonedCompanyId, poisonedBranchId, poisonNowMillis))
                .as("the previous method's permission set must NOT be served to the user that "
                        + "inherited its id — this is the SetPasswordAuthorityCeilingIT failure")
                .isEmpty();

        assertThat(companyTenantIndex.organisationOf(poisonedCompanyId))
                .as("the tenant index must report THIS method's organisation, not the previous "
                        + "method's — this is the TwoOrganisationIsolationIT failure")
                .isEqualTo(org.getId());
    }
}
