package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreateEmployeeRequest;
import com.erp.modules.hr.domain.dto.EmployeeDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EmployeeService {

    EmployeeDto create(CreateEmployeeRequest req);

    EmployeeDto getByUid(String uid);

    Page<EmployeeDto> listByCompany(Long companyId, Pageable pageable);

    EmployeeDto update(String uid, CreateEmployeeRequest req);

    void archive(String uid);
}
