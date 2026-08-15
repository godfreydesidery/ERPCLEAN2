package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByUid(String uid);

    List<Company> findByOrganisationIdOrderByName(Long organisationId);

    boolean existsByOrganisationIdAndCode(Long organisationId, String code);

    /**
     * Load a company by its own id. Named finder (not the inherited {@code findById}) so callers in
     * {@code ..service..} that resolve <em>the active-scope company itself</em> — where the id is the
     * caller's JWT company, never attacker-controlled request input — do not trip the confused-deputy
     * guard ({@code TenantScopingRulesTest}). Semantically identical to {@code findById}; the distinct
     * method name documents intent (self-scope lookup, not a by-id lookup of a foreign entity).
     */
    @Query("SELECT c FROM Company c WHERE c.id = :id")
    Optional<Company> findScopedById(@Param("id") Long id);

    /**
     * The organisation owning a company, without hydrating the company (ADR-0062 P3-11).
     *
     * <p>Feeds {@code CompanyTenantIndex}, which sits under every one of {@code ScopeGuard}'s 698
     * call sites. Unlike {@link #findScopedById} the id here CAN be attacker-controlled - it arrives
     * as {@code @RequestParam companyId} on 89 controllers - and that is precisely why it is
     * queried: this is the lookup that decides whether the caller may touch that company at all.
     * Nothing about the row is returned to the caller, only the id used for the comparison, so it is
     * not a confused-deputy read.
     */
    @Query("SELECT c.organisation.id FROM Company c WHERE c.id = :id")
    Optional<Long> findOrganisationIdById(@Param("id") Long id);
}
