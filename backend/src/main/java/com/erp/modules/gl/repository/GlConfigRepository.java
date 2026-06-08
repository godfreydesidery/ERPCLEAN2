package com.erp.modules.gl.repository;

import com.erp.modules.gl.domain.entity.GlConfig;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GlConfigRepository extends JpaRepository<GlConfig, Long> {

    Optional<GlConfig> findByUid(String uid);

    Optional<GlConfig> findByCompanyIdAndConfigKey(Long companyId, GlConfigKey configKey);

    List<GlConfig> findByCompanyId(Long companyId);

    /** Single-column projection for ScopeGuard case "glconfig" (ADR-0013 D-10). */
    @Query("SELECT g.companyId FROM GlConfig g WHERE g.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
