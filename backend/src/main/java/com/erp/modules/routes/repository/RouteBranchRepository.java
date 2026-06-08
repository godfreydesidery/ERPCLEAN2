package com.erp.modules.routes.repository;

import com.erp.modules.routes.domain.entity.RouteBranch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteBranchRepository extends JpaRepository<RouteBranch, Long> {

    Optional<RouteBranch> findByRouteIdAndBranchId(Long routeId, Long branchId);

    List<RouteBranch> findByRouteId(Long routeId);

    void deleteByRouteIdAndBranchId(Long routeId, Long branchId);
}
