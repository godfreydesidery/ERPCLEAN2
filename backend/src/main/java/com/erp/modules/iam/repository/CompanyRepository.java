package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByUid(String uid);

    List<Company> findByOrganisationIdOrderByName(Long organisationId);

    boolean existsByOrganisationIdAndCode(Long organisationId, String code);
}
