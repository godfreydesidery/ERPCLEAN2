package com.erp.modules.routes.repository;

import com.erp.modules.routes.domain.entity.RouteAgent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteAgentRepository extends JpaRepository<RouteAgent, Long> {

    Optional<RouteAgent> findByRouteIdAndAgentId(Long routeId, Long agentId);

    List<RouteAgent> findByRouteId(Long routeId);

    /**
     * Find the agent's primary route row for the invoice-default lookup (ADR-0012 D-6b).
     * The DB partial unique guarantees at most one row returns.
     */
    Optional<RouteAgent> findByAgentIdAndIsPrimaryTrue(Long agentId);

    /**
     * All primary-route rows for an agent — used to clear any prior primary when setting a new one
     * (BR-ROUTE-04, service-must pattern; the DB partial unique also enforces at-most-one).
     */
    List<RouteAgent> findByAgentIdAndIsPrimaryTrueAndRouteIdNot(Long agentId, Long excludeRouteId);

    void deleteByRouteIdAndAgentId(Long routeId, Long agentId);
}
