package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PosSaleIdempotency;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence port for the POS-sale idempotency marker (ADR-0042 D-1).
 *
 * <p>{@link #tryReserve} is the reserve step: a native {@code INSERT ... ON CONFLICT DO NOTHING}
 * that returns {@code 1} when this caller claimed the key, or {@code 0} when it already exists.
 * On an in-flight winner the insert BLOCKS until that transaction commits (then 0) or rolls back
 * (then 1) — standard Postgres behaviour — so two concurrent duplicates never both proceed, and the
 * loser never throws (no rollback-marked transaction).
 */
public interface PosSaleIdempotencyRepository extends JpaRepository<PosSaleIdempotency, Long> {

    Optional<PosSaleIdempotency> findByCompanyIdAndIdemKey(Long companyId, String idemKey);

    /** Reserve the key. Returns 1 if claimed by this caller, 0 if already taken (duplicate). */
    @Modifying
    @Query(value = """
            INSERT INTO pos_sale_idempotency (company_id, idem_key, created_at)
            VALUES (:companyId, :idemKey, now())
            ON CONFLICT (company_id, idem_key) DO NOTHING
            """, nativeQuery = true)
    int tryReserve(@Param("companyId") Long companyId, @Param("idemKey") String idemKey);

    /** Stamp the created invoice onto the reserved marker (same TX as the sale). */
    @Modifying
    @Query("""
            UPDATE PosSaleIdempotency p SET p.invoiceUid = :invoiceUid
            WHERE p.companyId = :companyId AND p.idemKey = :idemKey
            """)
    void stampInvoiceUid(@Param("companyId") Long companyId,
                         @Param("idemKey") String idemKey,
                         @Param("invoiceUid") String invoiceUid);
}
