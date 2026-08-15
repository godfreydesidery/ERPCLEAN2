package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.CompanyRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Locks the two properties {@link CompanyTenantIndex} is relied upon for (ADR-0062 P3-11): it must
 * be cheap, because it sits under 698 {@code ScopeGuard.canActIn} call sites, and it must never
 * invent a tenant for a company that has none.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the company → tenant index")
class CompanyTenantIndexTest {

    @Mock private CompanyRepository companies;

    @Test
    @DisplayName("resolves once and serves the rest from memory")
    void cachesTheMapping() {
        when(companies.findOrganisationIdById(5L)).thenReturn(Optional.of(42L));
        CompanyTenantIndex index = new CompanyTenantIndex(companies);

        assertThat(index.organisationOf(5L)).isEqualTo(42L);
        assertThat(index.organisationOf(5L)).isEqualTo(42L);
        assertThat(index.organisationOf(5L)).isEqualTo(42L);

        // The whole reason a cache is acceptable here: company → organisation is write-once, so
        // three calls on the hot path cost one query. If this ever becomes three queries, canActIn
        // has just put a database round-trip under every authorisation decision in the product.
        verify(companies, times(1)).findOrganisationIdById(5L);
    }

    @Test
    @DisplayName("a company that does not exist resolves to null, and is not cached as an answer")
    void anUnknownCompanyIsNotCached() {
        when(companies.findOrganisationIdById(99L)).thenReturn(Optional.empty());
        CompanyTenantIndex index = new CompanyTenantIndex(companies);

        assertThat(index.organisationOf(99L)).isNull();
        assertThat(index.organisationOf(99L)).isNull();

        // A miss must stay a miss: a company created after this JVM started has to resolve without a
        // restart. Caching the absence would make a brand-new company permanently unresolvable.
        verify(companies, times(2)).findOrganisationIdById(99L);
    }

    @Test
    @DisplayName("a null company id never reaches the database")
    void aNullCompanyIdShortCircuits() {
        CompanyTenantIndex index = new CompanyTenantIndex(companies);

        assertThat(index.organisationOf(null)).isNull();
        verifyNoInteractions(companies);
    }
}
