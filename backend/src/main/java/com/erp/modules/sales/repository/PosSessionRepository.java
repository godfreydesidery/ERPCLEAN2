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

    Page<PosSession> findByCompanyId(Long companyId, Pageable pageable);

    /** Paged sessions filtered by status — used by the POS checkout to load only OPEN sessions. */
    Page<PosSession> findByCompanyIdAndStatus(Long companyId, PosSessionStatus status, Pageable pageable);

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
