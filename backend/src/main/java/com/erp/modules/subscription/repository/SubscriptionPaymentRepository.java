package com.erp.modules.subscription.repository;

import com.erp.modules.subscription.domain.entity.SubscriptionPayment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only payment history (ADR-0069 D-8). Callers only save and read. */
public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {

    List<SubscriptionPayment> findByOrganisationIdOrderByPaidOnDescIdDesc(Long organisationId);
}
