package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PosSessionPayout;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PosSessionPayoutRepository extends JpaRepository<PosSessionPayout, Long> {

    Optional<PosSessionPayout> findByUid(String uid);

    @Query("SELECT p.companyId FROM PosSessionPayout p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /**
     * Total payouts for expected-cash computation (ADR-0029 D-3).
     * All payout types (REFUND, PAID_OUT) are outflows — they reduce the expected cash in
     * the drawer. Returns the SUM of all payout amounts (always >= 0); the caller subtracts
     * this from (opening_float + cash_tenders) to get expected_cash.
     */
    @Query("""
            SELECT COALESCE(SUM(p.amount), 0)
            FROM PosSessionPayout p
            WHERE p.posSessionId = :sessionId
            """)
    BigDecimal totalPayoutsForSession(@Param("sessionId") Long sessionId);
}
