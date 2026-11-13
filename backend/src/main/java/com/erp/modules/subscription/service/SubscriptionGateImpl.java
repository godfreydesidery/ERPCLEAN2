package com.erp.modules.subscription.service;

import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.subscription.domain.enums.CapResource;
import com.erp.modules.subscription.domain.enums.SubscriptionStage;
import com.erp.modules.subscription.repository.OrganisationSubscriptionRepository;
import com.erp.platform.common.api.ConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached per organisation (one subscription row, read on almost every request). The STAGE is not
 * cached: it is computed from the cached dates on every call, so crossing midnight into read-only
 * takes effect at once. The TTL is only a backstop for a change made outside
 * {@link SubscriptionService} (a direct UPDATE, a restored backup).
 */
@Service
public class SubscriptionGateImpl implements SubscriptionGate {

    private static final long TTL_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final OrganisationSubscriptionRepository subscriptions;
    private final OrganisationRepository organisations;
    private final Clock clock;
    private final com.erp.platform.security.CompanyTenantIndex companyTenants;
    private final ConcurrentHashMap<Long, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Optional<Entitlement> entitlement, long expiresAt) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SubscriptionGateImpl(OrganisationSubscriptionRepository subscriptions,
                                OrganisationRepository organisations,
                                com.erp.platform.security.CompanyTenantIndex companyTenants) {
        this(subscriptions, organisations, companyTenants, Clock.systemUTC());
    }

    SubscriptionGateImpl(OrganisationSubscriptionRepository subscriptions,
                         OrganisationRepository organisations,
                         com.erp.platform.security.CompanyTenantIndex companyTenants, Clock clock) {
        this.subscriptions = subscriptions;
        this.organisations = organisations;
        this.companyTenants = companyTenants;
        this.clock = clock;
    }

    @Override
    public Optional<Entitlement> entitlementOf(Long organisationId) {
        if (organisationId == null) {
            return Optional.empty();
        }
        long now = clock.millis();
        Cached hit = cache.get(organisationId);
        if (hit != null && hit.expiresAt() > now) {
            return hit.entitlement();
        }
        Optional<Entitlement> loaded = subscriptions.findByOrganisationId(organisationId)
                .map(s -> Entitlement.of(s, zoneOf(organisationId)));
        cache.put(organisationId, new Cached(loaded, now + TTL_MILLIS));
        return loaded;
    }

    @Override
    public SubscriptionStage stageOf(Long organisationId) {
        return entitlementOf(organisationId)
                .map(e -> e.stageOn(e.today(clock)))
                .orElse(SubscriptionStage.UNRESTRICTED);
    }

    @Override
    public boolean permits(Long organisationId, String permissionCode) {
        return entitlementOf(organisationId).map(e -> e.permits(permissionCode)).orElse(true);
    }

    @Override
    public Instant readOnlySince(Long organisationId) {
        return entitlementOf(organisationId)
                .filter(e -> e.stageOn(e.today(clock)).blocksWrites())
                .map(e -> e.readOnlyFrom().atStartOfDay(e.zone()).toInstant())
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertCanAdd(Long organisationId, CapResource resource) {
        Optional<Entitlement> entitlement = entitlementOf(organisationId);
        if (entitlement.isEmpty()) {
            return;
        }
        Integer cap = entitlement.get().cap(resource);
        if (cap == null) {
            return;
        }
        long inUse = count(organisationId, resource);
        if (inUse >= cap) {
            throw new ConflictException(capMessage(entitlement.get(), resource, cap));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void assertCanAddInCompany(Long companyId, CapResource resource) {
        assertCanAdd(companyTenants.organisationOf(companyId), resource);
    }

    /** How many of {@code resource} the tenant has now (what a cap is checked against). */
    long count(Long organisationId, CapResource resource) {
        return switch (resource) {
            case USERS -> subscriptions.countActiveUsers(organisationId);
            case BRANCHES -> subscriptions.countBranches(organisationId);
            case TILLS -> subscriptions.countActiveTills(organisationId);
            case COMPANIES -> subscriptions.countCompanies(organisationId);
        };
    }

    static String capMessage(Entitlement e, CapResource resource, int cap) {
        String plan = e.plan().displayName();
        return cap == 1
                ? "Your " + plan + " plan allows 1 " + singular(resource) + ", and it is in use. "
                        + "Ask your provider to upgrade your plan or raise the limit."
                : "Your " + plan + " plan allows up to " + cap + " " + resource.noun()
                        + ", and all of them are in use. "
                        + "Ask your provider to upgrade your plan or raise the limit.";
    }

    private static String singular(CapResource resource) {
        return switch (resource) {
            case USERS -> "user";
            case BRANCHES -> "branch";
            case TILLS -> "POS till";
            case COMPANIES -> "company";
        };
    }

    @Override
    public void evict(Long organisationId) {
        if (organisationId != null) {
            cache.remove(organisationId);
        }
    }

    /** Test seam: forget everything (integration tests reuse organisation ids across methods). */
    public void clear() {
        cache.clear();
    }

    LocalDate today(Entitlement e) {
        return e.today(clock);
    }

    private ZoneId zoneOf(Long organisationId) {
        // Scoped finder: the id is the caller's own organisation (from its principal) or one the
        // platform operator already loaded by uid — never raw request input.
        return organisations.findScopedById(organisationId)
                .map(o -> {
                    try {
                        return ZoneId.of(o.getDefaultTimeZone());
                    } catch (RuntimeException badZone) {
                        return FALLBACK_ZONE;
                    }
                })
                .orElse(FALLBACK_ZONE);
    }
}
