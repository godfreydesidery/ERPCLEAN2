package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.BlanketOrderDto;
import com.erp.modules.sales.domain.dto.CreateBlanketOrderRequest;
import com.erp.modules.sales.domain.dto.DrawBlanketRequest;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Blanket order lifecycle (ADR-0029 D-7).
 */
public interface BlanketOrderService {

    BlanketOrderDto createBlanket(CreateBlanketOrderRequest request);

    BlanketOrderDto getBlanketByUid(String uid);

    Page<BlanketOrderDto> listBlankets(Long companyId, Pageable pageable);

    void cancelBlanket(String uid);

    /**
     * Draw a sales order against a blanket, decrementing drawn_qty_base on affected lines.
     * Throws ConflictException if any line's draw would exceed committed_qty_base.
     *
     * @return the generated SalesOrder DTO
     */
    SalesOrderDto drawAgainstBlanket(DrawBlanketRequest request);
}
