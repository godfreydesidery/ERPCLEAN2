package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUid(String uid);

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);
}
