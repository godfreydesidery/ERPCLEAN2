package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreateDepartmentRequest;
import com.erp.modules.hr.domain.dto.DepartmentDto;
import java.util.List;

public interface DepartmentService {

    DepartmentDto create(CreateDepartmentRequest req);

    DepartmentDto getByUid(String uid);

    List<DepartmentDto> listByCompany(Long companyId);

    DepartmentDto update(String uid, CreateDepartmentRequest req);

    void deactivate(String uid);
}
