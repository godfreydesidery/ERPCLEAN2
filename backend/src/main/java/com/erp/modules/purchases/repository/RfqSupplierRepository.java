package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.RfqSupplier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RfqSupplierRepository extends JpaRepository<RfqSupplier, Long> {

    List<RfqSupplier> findByRfqId(Long rfqId);

    Optional<RfqSupplier> findByRfqIdAndSupplierId(Long rfqId, Long supplierId);

    boolean existsByRfqIdAndSupplierId(Long rfqId, Long supplierId);
}
