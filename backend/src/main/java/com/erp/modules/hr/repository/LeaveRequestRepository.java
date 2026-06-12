package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.LeaveRequest;
import com.erp.modules.hr.domain.enums.LeaveRequestStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    Optional<LeaveRequest> findByUid(String uid);

    @Query("SELECT r.companyId FROM LeaveRequest r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    Page<LeaveRequest> findByCompanyId(Long companyId, Pageable pageable);

    List<LeaveRequest> findByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

    List<LeaveRequest> findByCompanyIdAndStatus(Long companyId, LeaveRequestStatus status);

    /**
     * Returns approved unpaid leave requests for an employee that overlap the given pay period.
     * Used by PayrollRunServiceImpl to compute unpaid-leave pro-rata (ADR-0032 D-5, FR-HR-13).
     * A request overlaps [periodStart, periodEnd] when fromDate <= periodEnd AND toDate >= periodStart.
     */
    @Query("""
            SELECT r FROM LeaveRequest r
            JOIN LeaveType t ON t.id = r.leaveTypeId
            WHERE r.companyId   = :companyId
              AND r.employeeId  = :employeeId
              AND r.status      = com.erp.modules.hr.domain.enums.LeaveRequestStatus.APPROVED
              AND t.paid        = false
              AND r.fromDate   <= :periodEnd
              AND r.toDate     >= :periodStart
            """)
    List<LeaveRequest> findApprovedUnpaidOverlapping(Long companyId, Long employeeId,
                                                      LocalDate periodStart, LocalDate periodEnd);

    /**
     * Sum of days on approved unpaid leave requests overlapping the pay period for an employee.
     * Returns 0 if no matching rows (COALESCE).
     */
    @Query("""
            SELECT COALESCE(SUM(r.days), 0) FROM LeaveRequest r
            JOIN LeaveType t ON t.id = r.leaveTypeId
            WHERE r.companyId   = :companyId
              AND r.employeeId  = :employeeId
              AND r.status      = com.erp.modules.hr.domain.enums.LeaveRequestStatus.APPROVED
              AND t.paid        = false
              AND r.fromDate   <= :periodEnd
              AND r.toDate     >= :periodStart
            """)
    BigDecimal sumApprovedUnpaidDaysOverlapping(Long companyId, Long employeeId,
                                                 LocalDate periodStart, LocalDate periodEnd);
}
