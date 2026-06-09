package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArWriteOffDto;
import com.erp.modules.ar.domain.dto.WriteOffRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ArWriteOffService {

    /** Write off an uncollectable open item (FR-AR-13, BR-AR-06). Posts to GL synchronously. */
    ArWriteOffDto writeOff(WriteOffRequest req);

    ArWriteOffDto getByUid(String uid);

    Page<ArWriteOffDto> listByCompany(Long companyId, Pageable pageable);
}
