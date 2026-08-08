package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.dto.PayoutSubtotalDto;
import com.erp.modules.sales.domain.entity.PosSessionPayout;
import com.erp.modules.sales.domain.enums.PosPayoutType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
     * All payout types (REFUND, PAID_OUT, EXPENSE) are outflows — they reduce the expected cash in
     * the drawer. Returns the SUM of all payout amounts (always >= 0); the caller subtracts
     * this from (opening_float + cash_tenders) to get expected_cash.
     */
    @Query("""
            SELECT COALESCE(SUM(p.amount), 0)
            FROM PosSessionPayout p
            WHERE p.posSessionId = :sessionId
            """)
    BigDecimal totalPayoutsForSession(@Param("sessionId") Long sessionId);

    /**
     * The individual payouts behind the session's "Payouts" figure, oldest first (K8).
     *
     * <p>Only a SUM existed before, so nobody could list, print or question what the cash was spent
     * on. Ordered by recorded time then id so two reads of the same session are byte-identical, the
     * same guarantee the X/Z-read breakdown gives.
     */
    List<PosSessionPayout> findByPosSessionIdOrderByCreatedAtAscIdAsc(Long posSessionId);

    /**
     * Per-payout-type breakdown of the same figure {@link #totalPayoutsForSession} returns as a
     * single number — so an X/Z-read can print refunds, drawer drops and till expenses as three
     * separate lines instead of one merged "Payouts" line.
     *
     * <p>Only types actually recorded on the session come back; the service zero-fills the rest so
     * the printed layout is stable. Ordered by payout type for a deterministic read.
     */
    @Query("""
            SELECT new com.erp.modules.sales.domain.dto.PayoutSubtotalDto(
                       p.payoutType,
                       COALESCE(SUM(p.amount), 0),
                       COUNT(p))
            FROM PosSessionPayout p
            WHERE p.posSessionId = :sessionId
            GROUP BY p.payoutType
            ORDER BY p.payoutType
            """)
    List<PayoutSubtotalDto> payoutBreakdownForSession(@Param("sessionId") Long sessionId);

    /**
     * The company's till EXPENSES over a half-open instant range, oldest first (K8) — the rows
     * behind the cross-session expense report the owner asked for.
     *
     * <p>Filtered to a single {@code companyId} because that is the tenant boundary; the caller has
     * already asserted the principal may act in it. The payout type is passed as a parameter rather
     * than written as a JPQL enum literal so the query carries no hard-coded enum path.
     *
     * @param from  inclusive lower bound
     * @param until EXCLUSIVE upper bound — the caller passes the instant after the last reported
     *              day, so an expense recorded at 23:59 on the closing day is still counted
     */
    @Query("""
            SELECT p
            FROM PosSessionPayout p
            WHERE p.companyId  = :companyId
              AND p.payoutType = :payoutType
              AND p.createdAt >= :from
              AND p.createdAt <  :until
            ORDER BY p.createdAt ASC, p.id ASC
            """)
    List<PosSessionPayout> findExpensesForCompanyBetween(@Param("companyId") Long companyId,
                                                          @Param("payoutType") PosPayoutType payoutType,
                                                          @Param("from") Instant from,
                                                          @Param("until") Instant until);
}
