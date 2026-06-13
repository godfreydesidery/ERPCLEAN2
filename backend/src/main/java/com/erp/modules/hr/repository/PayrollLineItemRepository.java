package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.PayrollLineItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayrollLineItemRepository extends JpaRepository<PayrollLineItem, Long> {

    Optional<PayrollLineItem> findByUid(String uid);

    @Query("SELECT i.companyId FROM PayrollLineItem i WHERE i.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<PayrollLineItem> findByPayrollLineIdOrderByItemKindAscLabelAsc(Long payrollLineId);
}
