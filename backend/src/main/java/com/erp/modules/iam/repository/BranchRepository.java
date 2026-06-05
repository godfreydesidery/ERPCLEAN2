package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.Branch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    Optional<Branch> findByUid(String uid);

    List<Branch> findByCompanyIdOrderByName(Long companyId);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /** The current default branch of a company, if one is set (BR-2). */
    Optional<Branch> findByCompanyIdAndIsDefaultTrue(Long companyId);
}
