package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.AddOpportunityLineRequest;
import com.erp.modules.crm.domain.dto.AdvanceStageRequest;
import com.erp.modules.crm.domain.dto.CreateOpportunityRequest;
import com.erp.modules.crm.domain.dto.LoseOpportunityRequest;
import com.erp.modules.crm.domain.dto.OpportunityDto;
import com.erp.modules.crm.domain.dto.OpportunityLineDto;
import com.erp.modules.crm.domain.dto.UpdateOpportunityRequest;
import com.erp.modules.crm.domain.dto.WinOpportunityRequest;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OpportunityService {

    OpportunityDto create(CreateOpportunityRequest request);

    OpportunityDto getByUid(String uid);

    Page<OpportunityDto> list(Long companyId, Pageable pageable);

    OpportunityDto updateByUid(String uid, UpdateOpportunityRequest request);

    OpportunityLineDto addLine(String opportunityUid, AddOpportunityLineRequest request);

    void removeLine(String opportunityUid, String lineUid);

    List<OpportunityLineDto> listLines(String opportunityUid);

    OpportunityDto advanceStage(String uid, AdvanceStageRequest request);

    OpportunityDto win(String uid, WinOpportunityRequest request);

    OpportunityDto lose(String uid, LoseOpportunityRequest request);
}
