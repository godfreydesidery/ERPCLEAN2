package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.PurgeTenantRequestDto;
import com.erp.modules.iam.domain.dto.TenantPurgeReportDto;
import com.erp.modules.iam.domain.dto.TenantPurgeReportDto.TableRowCountDto;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditReadService;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.tenant.TenantExportService;
import com.erp.platform.tenant.TenantPurgeService;
import com.erp.platform.tenant.TenantPurgeService.PurgeReport;
import com.erp.platform.tenant.TenantRef;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT {@code @Transactional}: the export owns a read-only REPEATABLE READ transaction
 * of its own (its audit row is written in a separate one after the snapshot has ended), and the
 * purge owns one write transaction. The departure rules read the audit trail before that
 * transaction starts; the one rule a concurrent resume could race (still suspended) is re-checked
 * under a row lock inside it.
 */
@Service
public class TenantDepartureServiceImpl implements TenantDepartureService {

    private static final Logger log = LoggerFactory.getLogger(TenantDepartureServiceImpl.class);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    /** Grace period between suspension and deletion (owner decision 2026-11-07). */
    static final Duration MINIMUM_SUSPENSION = Duration.ofDays(30);

    /** The alias bootstrap gives the platform operator's own organisation. */
    private static final String PLATFORM_ALIAS = "platform";

    private static final String ORGANISATIONS = "organisations";

    private final OrganisationRepository organisations;
    private final AppUserRepository users;
    private final TenantExportService exporter;
    private final TenantPurgeService purger;
    private final AuditService audit;
    private final AuditReadService auditRead;
    private final Clock clock;

    public TenantDepartureServiceImpl(OrganisationRepository organisations, AppUserRepository users,
                                      TenantExportService exporter, TenantPurgeService purger,
                                      AuditService audit, AuditReadService auditRead, Clock clock) {
        this.organisations = organisations;
        this.users = users;
        this.exporter = exporter;
        this.purger = purger;
        this.audit = audit;
        this.auditRead = auditRead;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // R-7
    // -------------------------------------------------------------------------

    @Override
    public void exportTenant(String uid, ExportSink sink) throws IOException {
        Organisation org = requireTarget(uid);
        TenantRef ref = new TenantRef(org.getId(), org.getUid(), org.getName());

        String label = org.getAlias() != null ? org.getAlias() : "organisation";
        String fileName = "tenant-export-" + label.replaceAll("[^A-Za-z0-9_-]", "_") + "-"
                + STAMP.format(clock.instant()) + ".zip";

        OutputStream out = sink.open(fileName);
        exporter.export(ref, out, result -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("organisationUid", org.getUid());
            detail.put("organisationName", org.getName());
            detail.put("tables", String.valueOf(result.rowCounts().size()));
            detail.put("totalRows", String.valueOf(result.totalRows()));
            detail.put("flywayVersion", result.flywayVersion());
            detail.put("redactedColumns", String.join(",", result.redacted()));
            // Independent (REQUIRES_NEW): the snapshot transaction is read-only and already over.
            audit.recordIndependent(AuditEvent.of(AuditActions.ORG_EXPORT, ORGANISATIONS,
                    org.getId(), org.getUid()).detail(detail));
        });
        log.info("Tenant export streamed for organisation {} as {}", org.getId(), fileName);
    }

    // -------------------------------------------------------------------------
    // R-8
    // -------------------------------------------------------------------------

    @Override
    public TenantPurgeReportDto purgeTenant(String uid, PurgeTenantRequestDto request) {
        Organisation org = requireTarget(uid);
        RequestContext.Principal caller = RequestContext.get();
        TenantRef ref = new TenantRef(org.getId(), org.getUid(), org.getName());
        boolean dryRun = request == null || request.isDryRun();

        // Rule 4 - refused outright, dry run or not. One message for both cases so it does not say
        // which of the two applied.
        boolean own = caller.organisationId() != null && caller.organisationId().equals(org.getId());
        if (own || isPlatformOrganisation(org)) {
            log.warn("Purge refused: organisation {} is the caller's own or the platform's", org.getId());
            throw new ConflictException(
                    "This organisation is not a customer and cannot be removed.");
        }

        List<String> blockers = departureBlockers(org);

        if (dryRun) {
            PurgeReport report = purger.dryRun(ref);
            List<String> all = new ArrayList<>(blockers);
            if (!report.blockers().isEmpty()) {
                log.error("Purge dry run for organisation {}: plan blockers {}", org.getId(),
                        report.blockers());
                all.add("The data cannot be removed automatically right now; "
                        + "the technical reason has been logged.");
            }
            return toDto(org, report, all.isEmpty(), all);
        }

        // Rule 5. IllegalArgument -> 400 with this message.
        if (request.confirmName() == null || !org.getName().equals(request.confirmName())) {
            throw new IllegalArgumentException(
                    "The confirmation does not match the organisation's name. Type the name exactly.");
        }
        if (!blockers.isEmpty()) {
            throw new ConflictException(
                    "This organisation cannot be removed yet. " + String.join(" ", blockers));
        }

        PurgeReport report = purger.purge(ref, r -> audit.record(
                AuditEvent.of(AuditActions.ORG_PURGE, ORGANISATIONS, org.getId(), org.getUid())
                        .detail(purgeDetail(org, r))));
        log.warn("Organisation {} purged by user {}: {} rows", org.getId(), caller.userId(),
                report.totalRows());
        return toDto(org, report, true, List.of());
    }

    /**
     * Rules 1-3, as friendly sentences; empty when all hold. Everything is measured from the audit
     * trail because there is no suspended-at column (and none may be added).
     */
    private List<String> departureBlockers(Organisation org) {
        List<String> out = new ArrayList<>();
        Instant now = clock.instant();

        if (org.getStatus() != MasterStatus.INACTIVE) {
            out.add("The organisation must be suspended first.");
        }

        Optional<Instant> suspended = auditRead.latestAt(AuditActions.ORG_SUSPEND, ORGANISATIONS,
                org.getId());
        if (suspended.isEmpty()) {
            out.add("There is no record of the organisation being suspended.");
            return out;
        }
        Instant since = suspended.get();

        Optional<Instant> resumed = auditRead.latestAt(AuditActions.ORG_RESUME, ORGANISATIONS,
                org.getId());
        if (resumed.isPresent() && resumed.get().isAfter(since)) {
            out.add("The organisation was reactivated after it was suspended.");
        } else if (Duration.between(since, now).compareTo(MINIMUM_SUSPENSION) < 0) {
            out.add("The organisation must stay suspended for at least 30 days; it can be removed "
                    + "from " + DAY.format(since.plus(MINIMUM_SUSPENSION)) + ".");
        }

        Optional<Instant> exported = auditRead.latestAt(AuditActions.ORG_EXPORT, ORGANISATIONS,
                org.getId());
        if (exported.isEmpty() || !exported.get().isAfter(since)) {
            out.add("A complete export of the organisation's data must be made after it was "
                    + "suspended.");
        }
        return out;
    }

    private boolean isPlatformOrganisation(Organisation org) {
        return PLATFORM_ALIAS.equals(org.getAlias())
                || users.existsByOrganisationIdAndRootTrue(org.getId());
    }

    private static Map<String, Object> purgeDetail(Organisation org, PurgeReport r) {
        Map<String, Object> counts = new LinkedHashMap<>();
        r.tables().forEach(t -> counts.put(t.table(), t.rows()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("organisationUid", org.getUid());
        detail.put("organisationName", org.getName());
        detail.put("totalRows", r.totalRows());
        detail.put("rowsDeletedByTable", counts);
        return detail;
    }

    private static TenantPurgeReportDto toDto(Organisation org, PurgeReport r, boolean eligible,
                                              List<String> blockers) {
        return new TenantPurgeReportDto(org.getUid(), org.getName(), r.dryRun(), eligible,
                List.copyOf(blockers), r.totalRows(),
                r.tables().stream().map(t -> new TableRowCountDto(t.table(), t.rows())).toList());
    }

    /**
     * Loads the organisation the operator is acting on. NOT FOUND - never FORBIDDEN - for a caller
     * who is not the platform operator, and for a uid that names nothing, exactly as
     * {@code OrganisationServiceImpl.setStatus} does: the refusal must not reveal that the
     * organisation exists. Unlike setStatus there is no own-organisation exemption: a tenant's
     * administrator has no business exporting or purging even their own tenancy through this path.
     */
    private Organisation requireTarget(String uid) {
        RequestContext.Principal caller = RequestContext.get();
        if (!OrganisationServiceImpl.isPlatformOperator(caller)) {
            throw new NotFoundException("Organisation not found.");
        }
        return organisations.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Organisation not found."));
    }
}
