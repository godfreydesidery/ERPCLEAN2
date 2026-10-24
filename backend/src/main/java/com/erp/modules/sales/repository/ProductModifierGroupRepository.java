package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.ProductModifierGroup;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductModifierGroupRepository extends JpaRepository<ProductModifierGroup, Long> {

    List<ProductModifierGroup> findByCompanyId(Long companyId);

    List<ProductModifierGroup> findByCompanyIdAndProductId(Long companyId, Long productId);

    List<ProductModifierGroup> findByCompanyIdAndModifierGroupId(Long companyId, Long groupId);

    /** Clears a group's item list before the setup screen writes its replacement. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM ProductModifierGroup l WHERE l.companyId = :companyId "
            + "AND l.modifierGroupId = :groupId")
    int deleteByCompanyIdAndModifierGroupId(@Param("companyId") Long companyId,
                                            @Param("groupId") Long groupId);
}
