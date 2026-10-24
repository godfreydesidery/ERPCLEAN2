package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.ModifierGroup;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierGroupRepository extends JpaRepository<ModifierGroup, Long> {

    Optional<ModifierGroup> findByCompanyIdAndUid(Long companyId, String uid);

    List<ModifierGroup> findByCompanyIdOrderBySortOrderAscNameAsc(Long companyId);

    List<ModifierGroup> findByCompanyIdAndIdIn(Long companyId, Collection<Long> ids);

    boolean existsByCompanyIdAndNameIgnoreCase(Long companyId, String name);

    boolean existsByCompanyIdAndNameIgnoreCaseAndIdNot(Long companyId, String name, Long id);
}
