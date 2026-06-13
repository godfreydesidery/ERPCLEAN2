package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.ContractDto;
import com.erp.modules.hr.domain.dto.CreateContractRequest;
import java.util.List;

public interface ContractService {

    ContractDto create(CreateContractRequest req);

    ContractDto getByUid(String uid);

    List<ContractDto> listByEmployee(Long companyId, Long employeeId);

    void terminate(String uid);
}
