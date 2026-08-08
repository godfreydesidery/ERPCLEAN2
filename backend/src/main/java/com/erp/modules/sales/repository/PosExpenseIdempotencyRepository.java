package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PosExpenseIdempotency;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence port for the till-expense idempotency marker (V98).
 *
 * <p>Mirrors {@link PosSaleIdempotencyRepository}. {@link #tryReserve} is the reserve step: a native
 * {@code INSERT ... ON CONFLICT DO NOTHING} that returns {@code 1} when this caller claimed the key
 * and {@code 0} when it was already taken. Against an in-flight winner the insert BLOCKS until that
 * transaction commits (then 0) or rolls back (then 1) — standard Postgres behaviour — so two
 * concurrent duplicates never both proceed, and the loser never throws (no rollback-marked
 * transaction, so it can still read the winner's row and replay it).
 */
public interface PosExpenseIdempotencyRepository extends JpaRepository<PosExpenseIdempotency, Long> {

    Optional<PosExpenseIdempotency> findByCompanyIdAndIdemKey(Long companyId, String idemKey);

    /** Reserve the key. Returns 1 if claimed by this caller, 0 if already taken (duplicate). */
    @Modifying
    @Query(value = """
            INSERT INTO pos_expense_idempotency (company_id, pos_session_id, idem_key, created_at)
            VALUES (:companyId, :sessionId, :idemKey, now())
            ON CONFLICT (company_id, idem_key) DO NOTHING
            """, nativeQuery = true)
    int tryReserve(@Param("companyId") Long companyId,
                   @Param("sessionId") Long sessionId,
                   @Param("idemKey") String idemKey);

    /** Stamp the recorded payout onto the reserved marker (same TX as the expense). */
    @Modifying
    @Query("""
            UPDATE PosExpenseIdempotency p SET p.payoutUid = :payoutUid
            WHERE p.companyId = :companyId AND p.idemKey = :idemKey
            """)
    void stampPayoutUid(@Param("companyId") Long companyId,
                        @Param("idemKey") String idemKey,
                        @Param("payoutUid") String payoutUid);
}
