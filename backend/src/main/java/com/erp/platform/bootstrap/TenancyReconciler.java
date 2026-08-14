package com.erp.platform.bootstrap;

import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.RoleRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills in the parts of the tenancy spine that V101 deliberately left to application code
 * (MULTITENANCY-PLAN.md P1-3, ADR-0062): the organisation's {@code alias}, and
 * {@code roles.organisation_id} for roles the customer authored.
 *
 * <p>Idempotent, and runs on every boot. That is the point: it is the only thing that heals the
 * schema after a partial {@code pg_restore}, which the shipped restore command downgrades to a
 * warning, and after any window in which rows were created before the code that stamps them.
 *
 * <h2>Why roles are classified by seed uid and not by {@code is_system}</h2>
 *
 * {@code R__seed_permissions.sql} ends {@code ON CONFLICT (code) DO UPDATE ... SET is_system = true},
 * so a customer-authored role whose code collided with a shipped bundle code was <b>already</b>
 * flipped to {@code is_system} on some earlier deploy. {@code Role.createdBy} is never set anywhere
 * — no {@code setCreatedBy} call exists and there is no JPA auditing — so it cannot break the tie
 * either. The thirteen shipped roles have fixed literal uids, and that is the only discriminator
 * that survives: anything else risks stamping a shipped role with a tenant (breaking the partial
 * unique index that requires them NULL) or publishing a customer's own role to every tenant.
 *
 * <p>Any {@code is_system} role whose uid is NOT a seed uid is logged for review rather than
 * guessed at — it is a customer role the seed has adopted, and only the owner can say what should
 * happen to it.
 *
 * <h2>Scale</h2>
 *
 * Measured on the live estate: one organisation, ~21 roles. The plan's chunking and
 * run-before-the-connector-binds requirements were written for a whole-table {@code audit_logs}
 * rewrite that is no longer in scope; at this size the work is sub-millisecond and an
 * {@link ApplicationRunner} is the right shape. The advisory lock is kept regardless, because two
 * app instances starting together is the one case where cheap work still collides.
 */
@Component
@Order(30)   // after BootstrapRunner (unordered, runs last) and UserCompanyBackfill (20)
public class TenancyReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenancyReconciler.class);

    /** A stable, arbitrary key so two instances cannot reconcile at once. */
    private static final long ADVISORY_LOCK_KEY = 0x0A5E_1D62L;

    /**
     * The thirteen shipped roles, by uid: ORG_ADMIN from {@code V1__baseline.sql} plus the twelve
     * ADR-0057 bundles from {@code R__seed_permissions.sql}. These stay global — {@code
     * organisation_id IS NULL} — and V100's {@code uq_role_code_global} partial index depends on it.
     */
    private static final Set<String> SEED_ROLE_UIDS = Set.of(
            "0000000000XVKF7J9FAGX51RMQ",
            "00000000000000000000000001", "00000000000000000000000002",
            "00000000000000000000000003", "00000000000000000000000004",
            "00000000000000000000000005", "00000000000000000000000006",
            "00000000000000000000000007", "00000000000000000000000008",
            "00000000000000000000000009", "0000000000000000000000000A",
            "0000000000000000000000000B", "0000000000000000000000000C");

    private final OrganisationRepository organisations;
    private final RoleRepository roles;
    private final AppUserRepository users;
    private final JdbcTemplate jdbc;

    public TenancyReconciler(OrganisationRepository organisations, RoleRepository roles,
                             AppUserRepository users, JdbcTemplate jdbc) {
        this.organisations = organisations;
        this.roles = roles;
        this.users = users;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Transaction-scoped, so it is released at commit whatever happens. pg_try_advisory_lock is
        // SESSION-scoped and would leak for the life of a pooled connection.
        Boolean acquired = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                ADVISORY_LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("Tenancy reconcile skipped: another instance holds the lock.");
            return;
        }

        List<Organisation> all = organisations.findAll();
        if (all.size() == 1) {
            reconcileAlias(all.get(0));
            reconcileRoles(all.get(0));
        } else if (all.size() > 1) {
            // Multi-tenant: a role cannot be attributed by "the only organisation there is", and
            // guessing is exactly what this class must not do.
            log.info("Tenancy reconcile: {} organisations present — role attribution is left to "
                    + "provisioning, not inferred.", all.size());
        }
        reportResiduals();
    }

    /**
     * Derives an alias for an organisation that has none. Total and never throws: the alias is
     * user-facing but it is not worth refusing to start over, and a null alias silently passes
     * {@code ck_organisation_alias} anyway (a CHECK evaluating to NULL is satisfied), so a throw
     * here would be the only thing standing between a boot and a customer's morning.
     */
    private void reconcileAlias(Organisation org) {
        if (org.getAlias() != null && !org.getAlias().isBlank()) {
            return;
        }
        String slug = org.getName() == null ? "" : org.getName().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > 20) {
            slug = slug.substring(0, 20).replaceAll("-+$", "");
        }
        // ck_organisation_alias demands 2..20 chars, no leading or trailing hyphen. A name of "A"
        // or "!!!" would fail all of that, so fall back to something that cannot.
        if (slug.length() < 2) {
            slug = "org-" + org.getId();
        }
        org.setAlias(slug);
        organisations.save(org);
        log.info("Tenancy reconcile: organisation {} given alias '{}'.", org.getId(), slug);
    }

    /**
     * Stamps customer-authored roles with the sole organisation, and leaves the shipped thirteen
     * global.
     */
    private void reconcileRoles(Organisation org) {
        int stamped = 0;
        for (var role : roles.findAll()) {
            if (SEED_ROLE_UIDS.contains(role.getUid())) {
                continue;                       // shipped: must stay NULL (V100's partial index)
            }
            if (role.isSystem()) {
                // Adopted by the seed's ON CONFLICT ... SET is_system = true. Not ours to guess at.
                log.warn("Tenancy reconcile: role '{}' (uid {}) is is_system but is not a shipped "
                        + "role — the permission seed has adopted a customer role. Left unstamped "
                        + "for owner review.", role.getCode(), role.getUid());
                continue;
            }
            if (role.getOrganisationId() == null) {
                role.setOrganisationId(org.getId());
                stamped++;
            }
        }
        if (stamped > 0) {
            log.info("Tenancy reconcile: stamped {} customer role(s) with organisation {}.",
                    stamped, org.getId());
        }
    }

    /**
     * Reports what is still unattributed. Residual counts, not rows visited — the residual is the
     * number anyone actually needs, and it is the only signal that a partial restore left the
     * database half-expanded.
     */
    private void reportResiduals() {
        long usersNull = users.findAll().stream().filter(u -> u.getOrganisationId() == null).count();
        long rolesNull = roles.findAll().stream()
                .filter(r -> r.getOrganisationId() == null && !SEED_ROLE_UIDS.contains(r.getUid()))
                .count();
        if (usersNull > 0 || rolesNull > 0) {
            log.warn("Tenancy reconcile residual: {} user(s) and {} customer role(s) still have no "
                    + "organisation. P2-1's follow-up migration will refuse to apply NOT NULL until "
                    + "the user count is zero.", usersNull, rolesNull);
        } else {
            log.info("Tenancy reconcile: all users and customer roles are attributed.");
        }
    }
}
