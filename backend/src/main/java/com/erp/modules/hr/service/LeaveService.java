package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.DecideLeaveRequest;
import com.erp.modules.hr.domain.dto.LeaveRequestDto;
import com.erp.modules.hr.domain.dto.SubmitLeaveRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LeaveService {

    LeaveRequestDto submit(String employeeUid, SubmitLeaveRequest req);

    LeaveRequestDto getByUid(String uid);

    Page<LeaveRequestDto> listByCompany(Long companyId, Pageable pageable);

    LeaveRequestDto decide(String uid, DecideLeaveRequest req);
}
