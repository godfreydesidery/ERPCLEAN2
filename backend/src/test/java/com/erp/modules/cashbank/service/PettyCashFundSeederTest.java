package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.cashbank.domain.entity.PettyCashFund;
import com.erp.modules.cashbank.repository.PettyCashFundRepository;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link PettyCashFundSeeder} (ADR-0050 D-7 PR-B).
 *
 * <p>Covers idempotency (skip if the default code already exists), deferral when no branch exists
 * yet ({@code petty_cash_funds.branch_id} is {@code NOT NULL}), and the happy-path seed.
 */
class PettyCashFundSeederTest {

    private static final Long COMPANY_ID = 10L;
    private static final Long BRANCH_ID  = 20L;

    private PettyCashFundRepository funds;
    private CompanyRepository       companies;
    private BranchRepository        branches;
    private PettyCashFundSeeder     seeder;

    @BeforeEach
    void setUp() {
        funds     = mock(PettyCashFundRepository.class);
        companies = mock(CompanyRepository.class);
        branches  = mock(BranchRepository.class);
        seeder    = new PettyCashFundSeeder(funds, companies, branches);
    }

    @Test
    void seedDefaults_alreadyExists_isNoOp() {
        when(funds.existsByCompanyIdAndCode(COMPANY_ID, "PETTY")).thenReturn(true);

        seeder.seedDefaults(COMPANY_ID);

        verify(funds, never()).save(any());
        verify(branches, never()).findByCompanyIdAndIsDefaultTrue(any());
    }

    @Test
    void seedDefaults_noBranchYet_defersWithoutSaving() {
        when(funds.existsByCompanyIdAndCode(COMPANY_ID, "PETTY")).thenReturn(false);
        when(branches.findByCompanyIdAndIsDefaultTrue(COMPANY_ID)).thenReturn(Optional.empty());

        seeder.seedDefaults(COMPANY_ID);

        verify(funds, never()).save(any());
    }

    @Test
    void seedDefaults_branchExists_savesOneActiveFundWithZeroFloat() {
        when(funds.existsByCompanyIdAndCode(COMPANY_ID, "PETTY")).thenReturn(false);
        Branch branch = mock(Branch.class);
        when(branch.getId()).thenReturn(BRANCH_ID);
        when(branches.findByCompanyIdAndIsDefaultTrue(COMPANY_ID)).thenReturn(Optional.of(branch));
        Company company = mock(Company.class);
        when(company.getBaseCurrency()).thenReturn("TZS");
        when(companies.findScopedById(COMPANY_ID)).thenReturn(Optional.of(company));
        when(funds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        seeder.seedDefaults(COMPANY_ID);

        ArgumentCaptor<PettyCashFund> captor = ArgumentCaptor.forClass(PettyCashFund.class);
        verify(funds, times(1)).save(captor.capture());
        PettyCashFund fund = captor.getValue();
        assertThat(fund.getCompanyId()).isEqualTo(COMPANY_ID);
        assertThat(fund.getBranchId()).isEqualTo(BRANCH_ID);
        assertThat(fund.getCode()).isEqualTo("PETTY");
        assertThat(fund.getName()).isEqualTo("Petty Cash");
        assertThat(fund.getFloatAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fund.getBalanceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fund.getCurrency().value()).isEqualTo("TZS");
    }

    @Test
    void seedDefaults_noCurrencyConfigured_fallsBackToTZS() {
        when(funds.existsByCompanyIdAndCode(COMPANY_ID, "PETTY")).thenReturn(false);
        Branch branch = mock(Branch.class);
        when(branch.getId()).thenReturn(BRANCH_ID);
        when(branches.findByCompanyIdAndIsDefaultTrue(COMPANY_ID)).thenReturn(Optional.of(branch));
        when(companies.findScopedById(COMPANY_ID)).thenReturn(Optional.empty());
        when(funds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        seeder.seedDefaults(COMPANY_ID);

        ArgumentCaptor<PettyCashFund> captor = ArgumentCaptor.forClass(PettyCashFund.class);
        verify(funds).save(captor.capture());
        assertThat(captor.getValue().getCurrency().value()).isEqualTo("TZS");
    }
}
