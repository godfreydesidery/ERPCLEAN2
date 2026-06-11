package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.AddQuotationLineRequest;
import com.erp.modules.sales.domain.dto.CreateQuotationRequest;
import com.erp.modules.sales.domain.dto.QuotationDto;
import com.erp.modules.sales.domain.dto.QuotationLineDto;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface QuotationService {

    QuotationDto create(CreateQuotationRequest req);

    QuotationDto getByUid(String uid);

    Page<QuotationDto> list(Long companyId, Pageable pageable);

    QuotationLineDto addLine(String quotationUid, AddQuotationLineRequest req);

    void removeLine(String quotationUid, String lineUid);

    List<QuotationLineDto> listLines(String quotationUid);

    /** Allocates QUOTE-#### and transitions DRAFT → SENT. */
    QuotationDto send(String quotationUid);

    /** Transitions SENT → ACCEPTED and converts the quote to a SalesOrder (DRAFT). */
    SalesOrderDto accept(String quotationUid);

    /** Transitions SENT → REJECTED. */
    void reject(String quotationUid);
}
