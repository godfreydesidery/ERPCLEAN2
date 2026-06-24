package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.GoodsReceiptLineSerial;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link GoodsReceiptLineSerial} (V76).
 */
public interface GoodsReceiptLineSerialRepository extends JpaRepository<GoodsReceiptLineSerial, Long> {

    /** All serial rows for a given GR line. */
    List<GoodsReceiptLineSerial> findByGoodsReceiptLineId(Long goodsReceiptLineId);

    /** Bulk delete by line id — used on void reversal if needed. */
    void deleteByGoodsReceiptLineId(Long goodsReceiptLineId);
}
