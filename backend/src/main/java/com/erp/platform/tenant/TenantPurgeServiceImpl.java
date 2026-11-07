package com.erp.platform.tenant;

import com.erp.platform.common.api.ConflictException;
import com.erp.platform.tenant.TenantDataPlan.CycleBreak;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * See {@link TenantPurgeService} for the audit-trail decision.
 *
 * <h2>Why a plain {@code DELETE ... WHERE <filter>} per table is enough</h2>
 *
 * The plan orders tables so that every table is deleted only after every tenant table that
 * references it. A child's filter reads its parents, which therefore still exist when the child is
 * deleted. Self-referencing keys are NO ACTION (checked at statement end), so one statement removes
 * a whole hierarchy. The single mutual cycle is broken by nulling a nullable key first.
 *
 * <h2>Triggers</h2>
 *
 * Never disabled, not even for the session. The plan lists any trigger or rule that fires on the
 * statements purge runs; if one exists purge refuses (see {@link TenantDataPlan#deletionBlockers}).
 * Today the only trigger is {@code trg_role_code_not_reserved_by_global} (INSERT/UPDATE of
 * {@code roles}), which does not fire on DELETE.
 *
 * <h2>Consequence: names become free</h2>
 *
 * Deleting the organisation frees its alias and every {@code <user>@<alias>} username. A new tenant
 * may therefore register the same alias, and anyone holding the old credentials would meet the new
 * tenant's accounts of the same name. The platform operator should not reuse an alias soon after a
 * departure.
 */
@Service
class TenantPurgeServiceImpl implements TenantPurgeService {

    private static final Logger log = LoggerFactory.getLogger(TenantPurgeServiceImpl.class);

    static final String AUDIT_TABLE = "audit_logs";

    /** Shown when the tenant has audit history: audit rows are never deleted (invariant 7). */
    static final String AUDIT_HISTORY_REFUSAL = "This organisation has an audit history, and audit "
            + "records are never deleted, so it cannot be removed. Keep it suspended; its data can "
            + "still be exported.";

    private static final String FRIENDLY_REFUSAL =
            "This organisation's data could not be removed. Nothing was deleted. "
                    + "The technical reason has been logged.";

    private final TenantDataCatalog catalog;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;

    TenantPurgeServiceImpl(TenantDataCatalog catalog, JdbcTemplate jdbc,
                           PlatformTransactionManager txManager) {
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.txManager = txManager;
    }

    @Override
    public PurgeReport dryRun(TenantRef org) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> {
            TenantDataPlan plan = catalog.planFor(org.id());
            List<TableCount> counts = new ArrayList<>();
            long total = 0;
            for (TenantTable t : plan.tables()) {
                Long n = jdbc.queryForObject("SELECT count(*) FROM " + TenantDataCatalogImpl.q(t.name())
                        + " WHERE " + t.rowFilterSql(), Long.class);
                long rows = n == null ? 0 : n;
                counts.add(new TableCount(t.name(), rows));
                total += rows;
            }
            return new PurgeReport(true, counts, total, plan.deletionBlockers());
        });
    }

    @Override
    public PurgeReport purge(TenantRef org, Consumer<PurgeReport> auditInTransaction) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            return tx.execute(status -> {
                // Bounded waits: a held write lock fails the purge fast (clean rollback, retry),
                // rather than queueing every writer on the instance behind it. LOCAL = this
                // transaction only.
                jdbc.execute("SET LOCAL lock_timeout = '10s'");
                jdbc.execute("SET LOCAL statement_timeout = '30min'");

                // Serialises against a concurrent resume, and is the authoritative "still
                // suspended" check: the caller's audit-based checks ran before this lock.
                List<String> statuses = jdbc.queryForList(
                        "SELECT status FROM organisations WHERE id = ? FOR UPDATE", String.class,
                        org.id());
                if (statuses.isEmpty() || !"INACTIVE".equals(statuses.get(0))) {
                    log.warn("Purge of organisation {} refused: not suspended (status {})", org.id(),
                            statuses);
                    throw new ConflictException(
                            "The organisation must be suspended before its data can be removed.");
                }

                TenantDataPlan plan = catalog.planFor(org.id());
                if (!plan.deletionBlockers().isEmpty()) {
                    log.error("Purge of organisation {} refused, plan blockers: {}", org.id(),
                            plan.deletionBlockers());
                    throw new ConflictException(FRIENDLY_REFUSAL);
                }

                // AUDIT ROWS ARE NEVER DELETED (owner, 2026-11-07; CLAUDE.md invariant 7). Checked
                // here, inside the purge transaction, rather than only in the departure rules: a
                // sign-in attempt by one of the tenant's users between the dry run and this point
                // writes a new row, and this is the last place that can still refuse cleanly.
                TenantTable auditTable = plan.tables().stream()
                        .filter(x -> x.name().equals(AUDIT_TABLE)).findFirst().orElse(null);
                if (auditTable != null) {
                    Long history = jdbc.queryForObject("SELECT count(*) FROM "
                            + TenantDataCatalogImpl.q(AUDIT_TABLE) + " WHERE "
                            + auditTable.rowFilterSql(), Long.class);
                    if (history != null && history > 0) {
                        log.warn("Purge of organisation {} refused: {} audit row(s) of its own",
                                org.id(), history);
                        throw new ConflictException(AUDIT_HISTORY_REFUSAL);
                    }
                }

                for (CycleBreak cb : plan.cycleBreaks()) {
                    if (cb.table().equals(AUDIT_TABLE)) {
                        throw new IllegalStateException("A purge must never modify audit rows.");
                    }
                    TenantTable t = plan.tables().stream()
                            .filter(x -> x.name().equals(cb.table())).findFirst().orElseThrow();
                    String set = String.join(", ", cb.columns().stream()
                            .map(c -> TenantDataCatalogImpl.q(c) + " = NULL").toList());
                    int n = jdbc.update("UPDATE " + TenantDataCatalogImpl.q(cb.table()) + " SET "
                            + set + " WHERE " + t.rowFilterSql());
                    log.info("Purge {}: cleared {} on {} rows of {} to resolve a key cycle",
                            org.id(), cb.columns(), n, cb.table());
                }

                List<TableCount> counts = new ArrayList<>();
                long total = 0;
                for (TenantTable t : plan.tables()) {
                    if (t.name().equals(AUDIT_TABLE)) {
                        // Proven empty above; reported as zero, never deleted from.
                        counts.add(new TableCount(t.name(), 0));
                        continue;
                    }
                    int rows = jdbc.update("DELETE FROM " + TenantDataCatalogImpl.q(t.name())
                            + " WHERE " + t.rowFilterSql());
                    counts.add(new TableCount(t.name(), rows));
                    total += rows;
                    if (rows > 0) {
                        log.info("Purge {}: deleted {} rows from {}", org.id(), rows, t.name());
                    }
                }
                long orgRows = counts.stream().filter(c -> c.table().equals("organisations"))
                        .mapToLong(TableCount::rows).sum();
                if (orgRows != 1) {
                    // Cannot happen after the FOR UPDATE above; if it does, roll everything back.
                    throw new IllegalStateException("Organisation row not deleted exactly once.");
                }

                PurgeReport report = new PurgeReport(false, counts, total, List.of());
                auditInTransaction.accept(report);
                return report;
            });
        } catch (ConflictException e) {
            throw e;
        } catch (DataAccessException | IllegalStateException
                 | org.springframework.transaction.TransactionException e) {
            // A foreign key we did not anticipate, a lock timeout, a statement timeout. The whole
            // transaction has rolled back; tell the operator that, and keep the cause for the log.
            log.error("Purge of organisation {} failed and was rolled back", org.id(), e);
            throw new ConflictException(FRIENDLY_REFUSAL);
        }
    }
}
