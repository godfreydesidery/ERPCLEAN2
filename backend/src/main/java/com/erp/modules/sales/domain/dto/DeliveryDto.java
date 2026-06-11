package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.Delivery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DeliveryDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String deliveryNumber,
        Long salesOrderId,
        String salesOrderUid,
        String status,
        Long customerId,
        LocalDate deliveryDate,
        Instant confirmedAt,
        String notes,
        List<DeliveryLineDto> lines
) {
    public static DeliveryDto from(Delivery d, List<DeliveryLineDto> lines) {
        return new DeliveryDto(
                d.getId(), d.getUid(),
                d.getCompanyId(), d.getBranchId(),
                d.getDeliveryNumber(),
                d.getSalesOrderId(), d.getSalesOrderUid(),
                d.getStatus().name(),
                d.getCustomerId(),
                d.getDeliveryDate(),
                d.getConfirmedAt(),
                d.getNotes(),
                lines);
    }
}
