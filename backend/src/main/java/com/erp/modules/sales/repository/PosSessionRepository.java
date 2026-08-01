package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PosSessionRepository extends JpaRepository<PosSession, Long> {

    Optional<PosSession> findByUid(String uid);

    @Query("SELECT s.companyId FROM PosSession s WHERE s.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Optional<PosSession> findByPosTillIdAndStatus(Long posTillId, PosSessionStatus status);

    /**
     * Paged sessions for a company, <b>newest first</b>. The explicit order is load-bearing: the
     * till app reads page 0 to find the cashier's still-OPEN shift after a restart, and an
     * unordered page 0 can bury that row once the company has more lifetime sessions than fit on
     * one page — the shift then looks lost and the till reads as permanently occupied.
     */
    @Query(value = """
            SELECT s FROM PosSession s
            WHERE s.companyId = :companyId
            ORDER BY s.openedAt DESC, s.id DESC
            """,
            countQuery = "SELECT COUNT(s) FROM PosSession s WHERE s.companyId = :companyId")
    Page<PosSession> findByCompanyId(@Param("companyId") Long companyId, Pageable pageable);

    /**
     * Paged sessions filtered by status — used by the POS checkout to load only OPEN sessions.
     * Same newest-first order as {@link #findByCompanyId(Long, Pageable)} for the same reason.
     */
    @Query(value = """
            SELECT s FROM PosSession s
            WHERE s.companyId = :companyId
              AND s.status = :status
            ORDER BY s.openedAt DESC, s.id DESC
            """,
            countQuery = """
            SELECT COUNT(s) FROM PosSession s
            WHERE s.companyId = :companyId
              AND s.status = :status
            """)
    Page<PosSession> findByCompanyIdAndStatus(@Param("companyId") Long companyId,
                                              @Param("status") PosSessionStatus status,
                                              Pageable pageable);

    /** All invoices for reconciliation total computation. */
    @Query("""
            SELECT s FROM PosSession s
            WHERE s.companyId = :companyId
              AND s.status = :status
            """)
    List<PosSession> findByCompanyIdAndStatus(
            @Param("companyId") Long companyId,
            @Param("status") PosSessionStatus status);
}
