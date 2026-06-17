package com.erp.modules.fx.repository;

import com.erp.modules.fx.domain.entity.BranchCurrency;
import com.erp.platform.common.money.CurrencyCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the optional per-branch currency subset (ADR-0039 D-7).
 */
public interface BranchCurrencyRepository extends JpaRepository<BranchCurrency, Long> {

    Optional<BranchCurrency> findByUid(String uid);

    /** All rows for the branch (active + inactive). */
    List<BranchCurrency> findByBranchIdOrderByCurrencyCode(Long branchId);

    /** Active rows only — the branch's enabled subset. */
    List<BranchCurrency> findByBranchIdAndActiveTrueOrderByCurrencyCode(Long branchId);

    /** Count of rows for the branch (used to determine if branch has its own list). */
    int countByBranchId(Long branchId);

    /** Find a specific (branch, code) pair regardless of active flag. */
    Optional<BranchCurrency> findByBranchIdAndCurrencyCode(Long branchId, CurrencyCode currencyCode);

    /** Find a specific (branch, code) pair that is active. */
    Optional<BranchCurrency> findByBranchIdAndCurrencyCodeAndActiveTrue(
            Long branchId, CurrencyCode currencyCode);

    /** The current default document currency row for the branch. */
    Optional<BranchCurrency> findByBranchIdAndIsDefaultTrue(Long branchId);

    /** Whether any row exists for this branch/code (used for idempotent seeding). */
    boolean existsByBranchIdAndCurrencyCode(Long branchId, CurrencyCode currencyCode);
}
