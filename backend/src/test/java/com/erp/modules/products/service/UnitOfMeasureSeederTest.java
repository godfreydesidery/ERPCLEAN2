package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.DimensionType;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link UnitOfMeasureSeeder} — verifies weight/volume units get the correct physical
 * dimension on a fresh seed (ADR-0041 D5) and that a pre-existing physical unit still at the COUNT
 * default is reconciled to its true dimension (so weighed goods, ADR-0044 D-1b, resolve to WEIGHT),
 * while an admin-customised unit is left untouched.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnitOfMeasureSeederTest {

    private static final Long COMPANY = 7L;

    @Mock private UnitOfMeasureRepository units;

    @Test
    void seedDefaults_freshCompany_seedsKgAsWeightWithThreeDecimals() {
        when(units.existsByCompanyIdAndCode(eq(COMPANY), any())).thenReturn(false);
        when(units.findByCompanyIdAndCode(eq(COMPANY), any())).thenReturn(Optional.empty());

        new UnitOfMeasureSeeder(units).seedDefaults(COMPANY);

        ArgumentCaptor<UnitOfMeasure> saved = ArgumentCaptor.forClass(UnitOfMeasure.class);
        verify(units, times(UnitOfMeasureSeeder.DEFAULT_UNITS.size())).save(saved.capture());
        List<UnitOfMeasure> all = saved.getAllValues();

        UnitOfMeasure kg = byCode(all, "KG");
        assertThat(kg.getDimensionType()).isEqualTo(DimensionType.WEIGHT);
        assertThat(kg.getDecimalPlaces()).isEqualTo((short) 3);
        assertThat(kg.isFractional()).isTrue();

        UnitOfMeasure pcs = byCode(all, "PCS");
        assertThat(pcs.getDimensionType()).isEqualTo(DimensionType.COUNT);
    }

    @Test
    void seedDefaults_existingKgStillCount_reconciledToWeight() {
        // Every code already exists → nothing seeded; KG lingers at the pristine COUNT default.
        when(units.existsByCompanyIdAndCode(eq(COMPANY), any())).thenReturn(true);
        when(units.findByCompanyIdAndCode(eq(COMPANY), any())).thenReturn(Optional.empty());
        UnitOfMeasure kgCount = new UnitOfMeasure(COMPANY, "KG", "Kilogram", null); // defaults COUNT/0
        when(units.findByCompanyIdAndCode(COMPANY, "KG")).thenReturn(Optional.of(kgCount));

        new UnitOfMeasureSeeder(units).seedDefaults(COMPANY);

        verify(units).save(kgCount);
        assertThat(kgCount.getDimensionType()).isEqualTo(DimensionType.WEIGHT);
        assertThat(kgCount.getDecimalPlaces()).isEqualTo((short) 3);
    }

    @Test
    void seedDefaults_existingKgAlreadyWeight_notReclassified() {
        when(units.existsByCompanyIdAndCode(eq(COMPANY), any())).thenReturn(true);
        when(units.findByCompanyIdAndCode(eq(COMPANY), any())).thenReturn(Optional.empty());
        UnitOfMeasure kgWeight = new UnitOfMeasure(COMPANY, "KG", "Kilogram", null);
        kgWeight.setDimensionType(DimensionType.WEIGHT); // admin already set it
        kgWeight.setDecimalPlaces((short) 2);            // and chose 2 dp
        when(units.findByCompanyIdAndCode(COMPANY, "KG")).thenReturn(Optional.of(kgWeight));

        new UnitOfMeasureSeeder(units).seedDefaults(COMPANY);

        verify(units, never()).save(any());
        assertThat(kgWeight.getDecimalPlaces()).isEqualTo((short) 2); // admin choice preserved
    }

    private static UnitOfMeasure byCode(List<UnitOfMeasure> all, String code) {
        return all.stream().filter(u -> code.equals(u.getCode())).findFirst().orElseThrow();
    }
}
