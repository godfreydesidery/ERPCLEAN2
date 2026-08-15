package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.repository.PriceListRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit test for {@link DefaultPriceListProvisioner} — no Docker, no Spring context. */
@ExtendWith(MockitoExtension.class)
class DefaultPriceListProvisionerTest {

    @Mock PriceListRepository priceLists;

    @InjectMocks DefaultPriceListProvisioner provisioner;

    @Test
    @DisplayName("a supplied code and name become the company's one, default price list")
    void createsTheListFromTheSuppliedCodeAndName() {
        provisioner.createIfNamed(9L, "  RETAIL ", "  Retail Prices  ", null);

        PriceList saved = saved();

        assertThat(saved.getCompanyId()).isEqualTo(9L);
        assertThat(saved.getCode()).isEqualTo("RETAIL");
        assertThat(saved.getName()).isEqualTo("Retail Prices");
        // The only list in the company is its default, or every screen that resolves "the company
        // default price list" resolves nothing.
        assertThat(saved.isDefault()).isTrue();
        // Null currency means "the company's base" — a currency chosen here would be a second
        // opinion about the ledger currency.
        assertThat(saved.getCurrency()).isNull();
    }

    /**
     * The stance the operator states is the stance the list gets. Getting it wrong is invisible until
     * a customer complains: an EXCLUSIVE list holding VAT-inclusive shelf prices has 18% added to
     * every line, and {@code price_inclusive} is snapshotted per line, so correcting the flag
     * afterwards fixes nothing already sold.
     */
    @ParameterizedTest(name = "a stated VAT stance [{0}] is applied verbatim")
    @ValueSource(booleans = {true, false})
    void theStatedVatStanceIsApplied(boolean includesVat) {
        provisioner.createIfNamed(9L, "RETAIL", "Retail", includesVat);

        assertThat(saved().isPriceIncludesVat()).isEqualTo(includesVat);
    }

    @Test
    @DisplayName("an unstated VAT stance keeps the entity default rather than guessing one")
    void anUnstatedVatStanceKeepsTheEntityDefault() {
        provisioner.createIfNamed(9L, "RETAIL", "Retail", null);

        // Exclusive, matching PriceListServiceImpl.create with the flag omitted (ADR-0056 D-2).
        assertThat(saved().isPriceIncludesVat()).isFalse();
    }

    @ParameterizedTest(name = "code and name both blank [{0}] create no price list")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void blankCodeAndNameCreateNothing(String blank) {
        assertThat(provisioner.createIfNamed(9L, blank, blank, null)).isNull();

        verifyNoInteractions(priceLists);
    }

    /** A VAT stance on its own is not a price list: there is still nothing to name the row. */
    @Test
    @DisplayName("a VAT stance without a code or name creates nothing")
    void aVatStanceAloneCreatesNothing() {
        assertThat(provisioner.createIfNamed(9L, null, null, Boolean.TRUE)).isNull();

        verifyNoInteractions(priceLists);
    }

    /**
     * {@code price_lists.code} is NOT NULL, so a name on its own could only become a row by deriving
     * a code — inventing a value the operator did not supply. Saying so is the honest answer, and
     * the whole provisioning transaction rolls back so nothing half-made survives.
     */
    @Test
    @DisplayName("a name without a code is refused rather than given a derived code")
    void aNameWithoutACodeIsRefused() {
        assertThatThrownBy(() -> provisioner.createIfNamed(9L, "  ", "Retail", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");

        verifyNoInteractions(priceLists);
    }

    @Test
    @DisplayName("a code without a name is refused too")
    void aCodeWithoutANameIsRefused() {
        assertThatThrownBy(() -> provisioner.createIfNamed(9L, "RETAIL", null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(priceLists, org.mockito.Mockito.never()).save(any());
    }

    // -------------------------------------------------------------------------

    /** The one row the provisioner wrote. */
    private PriceList saved() {
        ArgumentCaptor<PriceList> captor = ArgumentCaptor.forClass(PriceList.class);
        verify(priceLists).save(captor.capture());
        return captor.getValue();
    }
}
