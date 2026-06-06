package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.OtherParty;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OtherPartyRepository extends JpaRepository<OtherParty, Long> {

    Optional<OtherParty> findByUid(String uid);

    Optional<OtherParty> findByCompanyIdAndUid(Long companyId, String uid);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    Page<OtherParty> findByCompanyId(Long companyId, Pageable pageable);

    @Query("""
            SELECT o FROM OtherParty o
            WHERE o.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(o.displayName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR o.tin = :q
                   OR o.phone = :q
                   OR o.code = :q)
            """)
    Page<OtherParty> search(@Param("companyId") Long companyId,
                            @Param("q") String q,
                            Pageable pageable);

    @Query("SELECT o.companyId FROM OtherParty o WHERE o.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
