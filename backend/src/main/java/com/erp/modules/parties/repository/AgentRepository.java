package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.Agent;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByUid(String uid);

    Optional<Agent> findByCompanyIdAndUid(Long companyId, String uid);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    Page<Agent> findByCompanyId(Long companyId, Pageable pageable);

    @Query("""
            SELECT a FROM Agent a
            WHERE a.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(a.displayName) LIKE LOWER(CONCAT(:q, '%'))
                   OR a.tin = :q
                   OR a.phone = :q
                   OR a.code = :q)
            """)
    Page<Agent> search(@Param("companyId") Long companyId,
                       @Param("q") String q,
                       Pageable pageable);

    @Query("SELECT a.companyId FROM Agent a WHERE a.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
