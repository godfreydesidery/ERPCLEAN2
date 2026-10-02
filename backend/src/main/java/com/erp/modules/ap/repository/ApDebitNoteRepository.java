package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.ApDebitNote;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApDebitNoteRepository extends JpaRepository<ApDebitNote, Long> {

    Optional<ApDebitNote> findByUid(String uid);

    /** ScopeGuard projection (ADR-0015 D-12). */
    @Query("SELECT d.companyId FROM ApDebitNote d WHERE d.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Optional<ApDebitNote> findByCompanyIdAndUid(Long companyId, String uid);

    Page<ApDebitNote> findByCompanyId(Long companyId, Pageable pageable);

    Page<ApDebitNote> findByCompanyIdAndSupplierId(Long companyId, Long supplierId, Pageable pageable);

    /**
     * Sum of the UNAPPLIED remainder of every debit note in a company — netted in
     * {@code ApReconciliationQuery} (mirror of {@code ArCreditNoteRepository.sumUnappliedByCompany}).
     *
     * <p>Every row here is posted: a debit note posts its full DR AP-control at raise and has no
     * draft or void state (UNAPPLIED / PARTIAL / APPLIED are application states only). The APPLIED
     * part is already inside the bills' outstanding, so only the unapplied remainder is netted —
     * summing {@code amount} would count the applied part twice.
     */
    @Query("""
            SELECT COALESCE(SUM(d.unappliedAmount), 0)
            FROM ApDebitNote d
            WHERE d.companyId = :companyId
            """)
    BigDecimal sumUnappliedByCompany(@Param("companyId") Long companyId);

    /** Per-supplier sibling of {@link #sumUnappliedByCompany} — netted in {@code ApBalanceService}. */
    @Query("""
            SELECT COALESCE(SUM(d.unappliedAmount), 0)
            FROM ApDebitNote d
            WHERE d.companyId = :companyId
              AND d.supplierId = :supplierId
            """)
    BigDecimal sumUnappliedByCompanyAndSupplier(@Param("companyId") Long companyId,
                                                @Param("supplierId") Long supplierId);
}
