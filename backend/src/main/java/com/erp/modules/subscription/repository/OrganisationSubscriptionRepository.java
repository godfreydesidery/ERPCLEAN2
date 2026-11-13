package com.erp.modules.subscription.repository;

import com.erp.modules.subscription.domain.entity.OrganisationSubscription;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Subscriptions, plus the four counts a cap is checked against (ADR-0069 D-5). The counts are native
 * SQL on purpose: they span IAM and POS tables, and counting by SQL keeps this module from importing
 * another module's repositories or entities.
 */
public interface OrganisationSubscriptionRepository
        extends JpaRepository<OrganisationSubscription, Long> {

    Optional<OrganisationSubscription> findByOrganisationId(Long organisationId);

    @Query(value = "SELECT count(*) FROM app_users u "
            + "WHERE u.organisation_id = :org AND u.status = 'ACTIVE'", nativeQuery = true)
    long countActiveUsers(@Param("org") Long organisationId);

    @Query(value = "SELECT count(*) FROM branches b JOIN companies c ON c.id = b.company_id "
            + "WHERE c.organisation_id = :org AND b.status <> 'ARCHIVED' "
            + "AND c.status <> 'ARCHIVED'", nativeQuery = true)
    long countBranches(@Param("org") Long organisationId);

    @Query(value = "SELECT count(*) FROM companies c "
            + "WHERE c.organisation_id = :org AND c.status <> 'ARCHIVED'", nativeQuery = true)
    long countCompanies(@Param("org") Long organisationId);

    @Query(value = "SELECT count(*) FROM pos_tills t JOIN companies c ON c.id = t.company_id "
            + "WHERE c.organisation_id = :org AND t.status = 'ACTIVE'", nativeQuery = true)
    long countActiveTills(@Param("org") Long organisationId);

    /** Who recorded a payment, for the history table (platform console only). */
    @Query(value = "SELECT u.username FROM app_users u WHERE u.id = :id", nativeQuery = true)
    Optional<String> usernameOf(@Param("id") Long userId);
}
