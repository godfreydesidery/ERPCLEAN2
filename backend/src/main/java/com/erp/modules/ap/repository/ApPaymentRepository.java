package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.ApPayment;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApPaymentRepository extends JpaRepository<ApPayment, Long> {

    Optional<ApPayment> findByUid(String uid);

    /** ScopeGuard projection (ADR-0015 D-12). */
    @Query("SELECT p.companyId FROM ApPayment p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Optional<ApPayment> findByCompanyIdAndUid(Long companyId, String uid);

    Page<ApPayment> findByCompanyId(Long companyId, Pageable pageable);

    Page<ApPayment> findByCompanyIdAndSupplierId(Long companyId, Long supplierId, Pageable pageable);

    /**
     * Update the payment total after all allocations are summed in a payment run.
     * Uses JPQL update to bypass the {@code updatable = false} JPA hint on {@code amount}.
     */
    @Modifying
    @Query("UPDATE ApPayment p SET p.amount = :amount WHERE p.id = :id")
    void updateAmount(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
