package com.erp.modules.tax.repository;

import com.erp.modules.tax.domain.entity.WhtTransaction;
import com.erp.modules.tax.domain.enums.WhtKind;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WhtTransactionRepository extends JpaRepository<WhtTransaction, Long> {

    Optional<WhtTransaction> findByUid(String uid);

    /** ScopeGuard projection (ADR-0017 D-11). */
    @Query("SELECT t.companyId FROM WhtTransaction t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<WhtTransaction> findByCompanyId(Long companyId, Pageable pageable);

    /** Period register: all WHT for a company in [start, end] by certificate_date (D-9 D-4). */
    List<WhtTransaction> findByCompanyIdAndCertificateDateBetween(
            Long companyId, LocalDate start, LocalDate end);

    /** By kind for the register split (WHT_ON_PAYMENT vs WHT_ON_RECEIPT). */
    List<WhtTransaction> findByCompanyIdAndKindAndCertificateDateBetween(
            Long companyId, WhtKind kind, LocalDate start, LocalDate end);
}
