package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.Position;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PositionRepository extends JpaRepository<Position, Long> {

    Optional<Position> findByUid(String uid);

    @Query("SELECT p.companyId FROM Position p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<Position> findByCompanyId(Long companyId);

    boolean existsByCompanyIdAndCode(Long companyId, String code);
}
