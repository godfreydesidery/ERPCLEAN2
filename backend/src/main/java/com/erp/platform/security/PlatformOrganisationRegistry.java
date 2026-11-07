package com.erp.platform.security;

import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.OrganisationRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Which organisation is the PLATFORM organisation (owner, 2026-11-07): the one whose alias is
 * {@code platform}. Aliases are immutable (D-7b) and a customer may not be given this one
 * ({@code OrganisationServiceImpl.createTenant}), so the alias is a stable identity.
 *
 * <p>Looked up once and cached: the write guard consults it on every non-GET request. The cache is
 * refreshed by {@link #reload()} when the platform organisation is created. One application node
 * only (P8-6), so there is no cross-node invalidation to worry about.
 */
@Component
public class PlatformOrganisationRegistry {

    public static final String PLATFORM_ALIAS = "platform";

    private final OrganisationRepository organisations;
    private volatile boolean loaded;
    private volatile Long platformId;

    public PlatformOrganisationRegistry(OrganisationRepository organisations) {
        this.organisations = organisations;
    }

    public Optional<Long> platformOrganisationId() {
        if (!loaded) {
            reload();
        }
        return Optional.ofNullable(platformId);
    }

    public boolean isPlatform(Long organisationId) {
        return organisationId != null && organisationId.equals(platformOrganisationId().orElse(null));
    }

    public synchronized void reload() {
        platformId = organisations.findByAlias(PLATFORM_ALIAS).map(Organisation::getId).orElse(null);
        loaded = true;
    }
}
