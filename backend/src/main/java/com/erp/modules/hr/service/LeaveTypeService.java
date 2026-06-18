package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreateLeaveTypeRequest;
import com.erp.modules.hr.domain.dto.LeaveTypeDto;
import java.util.List;

public interface LeaveTypeService {

    LeaveTypeDto create(CreateLeaveTypeRequest req);

    LeaveTypeDto getByUid(String uid);

    List<LeaveTypeDto> listByCompany(Long companyId);

    LeaveTypeDto update(String uid, CreateLeaveTypeRequest req);

    void deactivate(String uid);
}
