package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PosSessionPayout;
import com.erp.modules.sales.domain.enums.PosPayoutType;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PosSessionPayoutRepository extends JpaRepository<PosSessionPayout, Long> {

    Optional<PosSessionPayout> findByUid(String uid);

    @Query("SELECT p.companyId FROM PosSessionPayout p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /** Net payout delta for expected-cash computation (CASH_IN positive, CASH_OUT negative). */
    @Query("""
            SELECT COALESCE(
                SUM(CASE WHEN p.payoutType = 'CASH_IN'  THEN p.amount ELSE 0 END)
              - SUM(CASE WHEN p.payoutType = 'CASH_OUT' THEN p.amount ELSE 0 END),
              0)
            FROM PosSessionPayout p
            WHERE p.posSessionId = :sessionId
            """)
    BigDecimal netPayoutForSession(@Param("sessionId") Long sessionId);
}
