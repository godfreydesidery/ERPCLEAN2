package com.erp.modules.cashbank.repository;

import com.erp.modules.cashbank.domain.entity.CashCountDenomination;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashCountDenominationRepository extends JpaRepository<CashCountDenomination, Long> {

    List<CashCountDenomination> findByCashCountIdOrderByDenominationDesc(Long cashCountId);

    /**
     * Bulk-delete every line for a count — used by the replace-on-recount flow.
     * A JPQL bulk delete (not a derived remove) so it executes immediately against the DB,
     * guaranteeing the old rows are gone before the new lines are inserted in the same transaction
     * (avoids a spurious uq_cash_count_denom violation on a re-submitted denomination).
     */
    @Modifying
    @Query("DELETE FROM CashCountDenomination d WHERE d.cashCountId = :cashCountId")
    void deleteByCashCountId(@Param("cashCountId") Long cashCountId);
}
