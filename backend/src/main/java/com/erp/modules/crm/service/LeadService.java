package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.CreateLeadRequest;
import com.erp.modules.crm.domain.dto.DisqualifyLeadRequest;
import com.erp.modules.crm.domain.dto.LeadDto;
import com.erp.modules.crm.domain.dto.QualifyLeadRequest;
import com.erp.modules.crm.domain.dto.UpdateLeadRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LeadService {

    LeadDto create(CreateLeadRequest request);

    LeadDto getByUid(String uid);

    Page<LeadDto> list(Long companyId, Pageable pageable);

    LeadDto updateByUid(String uid, UpdateLeadRequest request);

    /** Qualify: link to existing customer or promote via CustomerService.create. */
    LeadDto qualify(String uid, QualifyLeadRequest request);

    /** CONTACTED transition — call after first interaction. */
    LeadDto contact(String uid);

    LeadDto disqualify(String uid, DisqualifyLeadRequest request);
}
