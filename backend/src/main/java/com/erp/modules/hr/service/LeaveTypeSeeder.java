package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.entity.LeaveType;
import com.erp.modules.hr.domain.enums.LeaveAccrualMethod;
import com.erp.modules.hr.repository.LeaveTypeRepository;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the statutory leave types for a newly provisioned company (ADR-0062 P5-5).
 *
 * <h2>Why this exists at all</h2>
 *
 * {@code V52__hr_leave_loans.sql:179-195} seeds these six rows with {@code CROSS JOIN companies} —
 * which covers every company that existed <b>when that migration ran</b>, and nothing since. A
 * migration cannot seed a row for a company created next year, and no Java path filled the gap. The
 * consequence is invisible until it matters: a new tenant opens HR → Leave to an empty list and
 * cannot record a single day of leave, with no error to explain why.
 *
 * <p>It only became reachable with multi-tenancy because until now every install got its company
 * from bootstrap on an empty database, where V52 had just run over it.
 *
 * <p>The six defaults mirror V52 exactly. They are Tanzanian statutory minima and are not a
 * suggestion: 28 days annual, 84 days maternity, 3 days paternity. Diverging from the migration
 * would give tenants provisioned by different routes different entitlements.
 */
@Service
public class LeaveTypeSeeder {

    private static final Logger log = LoggerFactory.getLogger(LeaveTypeSeeder.class);

    private record Default(String code, String name, boolean paid, String days) {
    }

    /** Mirrors V52__hr_leave_loans.sql:183-190 exactly — change both or neither. */
    private static final List<Default> DEFAULTS = List.of(
            new Default("ANNUAL", "Annual Leave", true, "28.0"),
            new Default("SICK", "Sick Leave", true, "10.0"),
            new Default("MATERNITY", "Maternity Leave", true, "84.0"),
            new Default("PATERNITY", "Paternity Leave", true, "3.0"),
            new Default("COMPASSION", "Compassionate Leave", true, "3.0"),
            new Default("UNPAID", "Unpaid Leave", false, "0.0"));

    private final LeaveTypeRepository leaveTypes;

    public LeaveTypeSeeder(LeaveTypeRepository leaveTypes) {
        this.leaveTypes = leaveTypes;
    }

    /**
     * Idempotent: mirrors V52's {@code ON CONFLICT (company_id, code) DO NOTHING}, so running it
     * against a company the migration already covered is a no-op rather than a duplicate-key error.
     */
    @Transactional
    public void seedDefaults(Long companyId) {
        if (companyId == null) {
            return;
        }
        int created = 0;
        for (Default d : DEFAULTS) {
            if (leaveTypes.existsByCompanyIdAndCode(companyId, d.code())) {
                continue;
            }
            leaveTypes.save(new LeaveType(companyId, d.code(), d.name(), d.paid(),
                    new BigDecimal(d.days()), LeaveAccrualMethod.ANNUAL_GRANT, null));
            created++;
        }
        if (created > 0) {
            log.info("Seeded {} default leave type(s) for company {}.", created, companyId);
        }
    }
}
