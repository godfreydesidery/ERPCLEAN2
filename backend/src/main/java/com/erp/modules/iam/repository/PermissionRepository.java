package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.Permission;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByCode(String code);

    List<Permission> findByCodeIn(List<String> codes);

    List<Permission> findByModuleOrderByCode(String module);
}
