package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.ConvertOpportunityRequest;
import com.erp.modules.crm.domain.dto.ConvertResultDto;

public interface OpportunityConversionService {

    /**
     * Converts a CRM opportunity to a quotation or sales order (ADR-0031 D-7).
     * Synchronous call into QuotationService/SalesOrderService; idempotent via
     * uq_opportunity_converted_document + convert guard.
     */
    ConvertResultDto convert(String opportunityUid, ConvertOpportunityRequest request);
}
