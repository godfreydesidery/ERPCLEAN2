package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.ActivityDto;
import com.erp.modules.crm.domain.dto.CreateActivityRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ActivityService {

    ActivityDto create(CreateActivityRequest request);

    ActivityDto getByUid(String uid);

    Page<ActivityDto> listByLead(String leadUid, Pageable pageable);

    Page<ActivityDto> listByOpportunity(String opportunityUid, Pageable pageable);

    Page<ActivityDto> listOpenTasks(Long companyId, Long assigneeUserId, Pageable pageable);

    /** Mark a TASK activity as done. */
    ActivityDto complete(String uid);
}
