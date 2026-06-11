package com.erp.modules.costing.repository;

import com.erp.modules.costing.domain.entity.Dimension;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DimensionRepository extends JpaRepository<Dimension, Long> {

    Optional<Dimension> findByUid(String uid);

    List<Dimension> findByCompanyId(Long companyId);

    Optional<Dimension> findByCompanyIdAndSlot(Long companyId, DimensionSlot slot);

    /** ScopeGuard case "dimension" (ADR-0025 D-5). */
    @Query("SELECT d.companyId FROM Dimension d WHERE d.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /**
     * GL re-asserts mandatory slots via this projection — reads dimensions.is_mandatory
     * without importing a costing service (ADR-0025 D-4, D-7 table-level read edge).
     */
    @Query("SELECT d.slot FROM Dimension d WHERE d.companyId = :companyId AND d.mandatory = true")
    List<DimensionSlot> findMandatorySlots(@Param("companyId") Long companyId);
}
