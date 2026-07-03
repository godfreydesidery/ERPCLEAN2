package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.SalesSettings;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesSettingsRepository extends JpaRepository<SalesSettings, Long> {

    Optional<SalesSettings> findByCompanyId(Long companyId);
}
