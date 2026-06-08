package com.erp.modules.routes.repository;

import com.erp.modules.routes.domain.entity.RouteCustomer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteCustomerRepository extends JpaRepository<RouteCustomer, Long> {

    Optional<RouteCustomer> findByRouteIdAndCustomerId(Long routeId, Long customerId);

    List<RouteCustomer> findByRouteId(Long routeId);

    void deleteByRouteIdAndCustomerId(Long routeId, Long customerId);
}
