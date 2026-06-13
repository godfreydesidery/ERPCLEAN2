package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreateLoanRequest;
import com.erp.modules.hr.domain.dto.EmployeeLoanDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EmployeeLoanService {

    EmployeeLoanDto create(String employeeUid, CreateLoanRequest req);

    EmployeeLoanDto getByUid(String uid);

    Page<EmployeeLoanDto> listByCompany(Long companyId, Pageable pageable);

    EmployeeLoanDto approve(String uid);

    void close(String uid);
}
