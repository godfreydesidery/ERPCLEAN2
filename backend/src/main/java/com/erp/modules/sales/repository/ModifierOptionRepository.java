package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.ModifierOption;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierOptionRepository extends JpaRepository<ModifierOption, Long> {

    List<ModifierOption> findByCompanyIdAndModifierGroupIdInOrderBySortOrderAscLabelAsc(
            Long companyId, Collection<Long> groupIds);

    List<ModifierOption> findByCompanyIdAndModifierGroupId(Long companyId, Long groupId);

    List<ModifierOption> findByCompanyIdAndUidIn(Long companyId, Collection<String> uids);
}
