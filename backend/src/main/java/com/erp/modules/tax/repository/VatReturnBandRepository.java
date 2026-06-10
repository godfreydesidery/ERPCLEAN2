package com.erp.modules.tax.repository;

import com.erp.modules.tax.domain.entity.VatReturnBand;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VatReturnBandRepository extends JpaRepository<VatReturnBand, Long> {

    List<VatReturnBand> findByVatReturnId(Long vatReturnId);

    /** Delete bands to rebuild on DRAFT recompute (D-4). */
    @Modifying
    @Query("DELETE FROM VatReturnBand b WHERE b.vatReturnId = :vatReturnId")
    void deleteByVatReturnId(@Param("vatReturnId") Long vatReturnId);
}
