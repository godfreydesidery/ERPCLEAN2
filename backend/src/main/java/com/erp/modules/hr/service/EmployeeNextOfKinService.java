package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreateNextOfKinRequest;
import com.erp.modules.hr.domain.dto.EmployeeNextOfKinDto;
import com.erp.modules.hr.domain.dto.UpdateNextOfKinRequest;
import java.util.List;

/**
 * CRUD for employee next-of-kin child records (ADR-0040 D-11).
 *
 * <p>Primary rule: at most one ACTIVE next-of-kin per employee is primary. When a record is
 * created/updated with {@code isPrimary=true}, any existing primary for that employee is cleared
 * first (within the same transaction).
 */
public interface EmployeeNextOfKinService {

    EmployeeNextOfKinDto add(String employeeUid, CreateNextOfKinRequest req);

    List<EmployeeNextOfKinDto> list(String employeeUid);

    EmployeeNextOfKinDto update(String employeeUid, String nokUid, UpdateNextOfKinRequest req);

    void deactivate(String employeeUid, String nokUid);
}
