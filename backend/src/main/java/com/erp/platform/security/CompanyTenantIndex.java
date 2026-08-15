package com.erp.platform.security;

import com.erp.modules.iam.repository.CompanyRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Answers "which tenant owns this company?" cheaply enough to sit on the hot path
 * (ADR-0062, MULTITENANCY-PLAN.md P3-11).
 *
 * <p>{@link ScopeGuard#canActIn} is reached from 698 call sites and several times per request, so it
 * cannot afford a query per call. It also cannot afford a stale answer, because here a stale answer
 * is a cross-tenant authorisation decision rather than an out-of-date screen — which rules out the
 * usual TTL cache.
 *
 * <h2>Why an unbounded, never-invalidated cache is the right shape</h2>
 *
 * {@code companies.organisation_id} is <b>write-once</b>. It is {@code NOT NULL} and set in the
 * {@code Company(organisation, code, name)} constructor; no setter for the organisation exists, no
 * update path assigns one, and moving a company between organisations would orphan every branch,
 * document sequence and ledger row beneath it. A cached entry therefore cannot go stale — the only
 * change the table admits is a new company, which simply misses and loads.
 *
 * <p>Size is bounded by the number of companies in the deployment, which is small by construction: a
 * tenant has a handful of legal entities, not a table of them.
 *
 * <p><b>If a company ever becomes movable between organisations, this class is the first thing that
 * must change.</b> A mapping that is safe to cache forever becomes a security bug the moment it can
 * change.
 */
@Component
public class CompanyTenantIndex {

    private final CompanyRepository companies;
    private final Map<Long, Long> cache = new ConcurrentHashMap<>();

    public CompanyTenantIndex(CompanyRepository companies) {
        this.companies = companies;
    }

    /**
     * The organisation owning {@code companyId}, or {@code null} when there is no such company.
     *
     * <p>A null here means "the tenant could not be established", never "this company has no tenant"
     * — the column is NOT NULL, so the only way to get one is a company id that does not exist.
     */
    public Long organisationOf(Long companyId) {
        if (companyId == null) {
            return null;
        }
        // Not computeIfAbsent: it cannot represent a miss, and a company created after this JVM
        // started must resolve without a restart. A miss costs one indexed lookup and happens once.
        Long cached = cache.get(companyId);
        if (cached != null) {
            return cached;
        }
        Long resolved = companies.findOrganisationIdById(companyId).orElse(null);
        if (resolved != null) {
            cache.put(companyId, resolved);
        }
        return resolved;
    }

    /** Test seam. The mapping is write-once, so this is for fixtures — not for invalidation. */
    void clear() {
        cache.clear();
    }
}
