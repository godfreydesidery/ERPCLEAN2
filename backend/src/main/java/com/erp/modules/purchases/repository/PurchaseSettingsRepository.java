package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.PurchaseSettings;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseSettingsRepository extends JpaRepository<PurchaseSettings, Long> {

    Optional<PurchaseSettings> findByCompanyId(Long companyId);
}
