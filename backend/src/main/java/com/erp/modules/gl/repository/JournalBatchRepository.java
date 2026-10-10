package com.erp.modules.gl.repository;

import com.erp.modules.gl.domain.entity.JournalBatch;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalBatchRepository extends JpaRepository<JournalBatch, Long> {

    Optional<JournalBatch> findByUid(String uid);

    /** Company-scoped batch lookup (never a bare by-id read from a service). */
    Optional<JournalBatch> findByIdAndCompanyId(Long id, Long companyId);
}
