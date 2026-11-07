package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PosOfflineReview;
import com.erp.modules.sales.domain.enums.OfflineReviewKind;
import com.erp.modules.sales.domain.enums.OfflineReviewStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosOfflineReviewRepository extends JpaRepository<PosOfflineReview, Long> {

    Optional<PosOfflineReview> findByUid(String uid);

    Page<PosOfflineReview> findByCompanyIdAndBranchIdAndStatusOrderByCreatedAtDesc(
            Long companyId, Long branchId, OfflineReviewStatus status, Pageable pageable);

    List<PosOfflineReview> findBySalesInvoiceId(Long salesInvoiceId);

    /** A HELD sale is kept once per offline number, so a repeated sync does not list it twice. */
    boolean existsByCompanyIdAndOfflineNumberAndKind(Long companyId, String offlineNumber,
                                                     OfflineReviewKind kind);
}
