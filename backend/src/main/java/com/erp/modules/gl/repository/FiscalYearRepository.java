package com.erp.modules.gl.repository;

import com.erp.modules.gl.domain.entity.FiscalYear;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalYearRepository extends JpaRepository<FiscalYear, Long> {

    Optional<FiscalYear> findByUid(String uid);

    Optional<FiscalYear> findByCompanyIdAndYearCode(Long companyId, String yearCode);

    List<FiscalYear> findByCompanyIdOrderByStartDateDesc(Long companyId);

    @Query("SELECT y.companyId FROM FiscalYear y WHERE y.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
