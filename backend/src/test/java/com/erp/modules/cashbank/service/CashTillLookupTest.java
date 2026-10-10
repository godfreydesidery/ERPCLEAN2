package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.cashbank.domain.dto.CashTillOptionDto;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * LRB-03 / ADM-01: the cash-count till picker must list only ACTIVE CASH-type accounts, as a narrow
 * row, and stay company-scoped (a cashier holding only the cash-count codes reaches this read).
 */
class CashTillLookupTest {

    private final CashBankAccountRepository accounts = mock(CashBankAccountRepository.class);
    private final ScopeGuard scopeGuard = mock(ScopeGuard.class);
    private CashBankAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CashBankAccountServiceImpl(accounts, mock(ChartOfAccountRepository.class),
                mock(CompanyRepository.class), mock(BranchRepository.class),
                mock(CashBankNumberGenerator.class), scopeGuard, mock(AuditService.class));
        RequestContext.set(new RequestContext.Principal(99L, "cashier", false, 1L, 5L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void listsOnlyActiveCashAccounts() {
        CashBankAccount till = new CashBankAccount(1L, 5L, "CA-01", "Front till",
                CashBankAccountType.CASH, "TZS", 10L, true, 1L);
        CashBankAccount closedTill = new CashBankAccount(1L, 5L, "CA-02", "Old till",
                CashBankAccountType.CASH, "TZS", 11L, false, 1L);
        closedTill.setActive(false);
        CashBankAccount bank = new CashBankAccount(1L, null, "BA-01", "CRDB current",
                CashBankAccountType.BANK, "TZS", 12L, false, 1L);
        when(accounts.findByCompanyId(1L)).thenReturn(List.of(till, closedTill, bank));

        List<CashTillOptionDto> rows = service.listCashTills(1L);

        assertThat(rows).extracting(CashTillOptionDto::code).containsExactly("CA-01");
        assertThat(rows.get(0).name()).isEqualTo("Front till");
        assertThat(rows.get(0).branchId()).isEqualTo(5L);
        assertThat(rows.get(0).currency()).isEqualTo("TZS");
    }

    @Test
    void refusesAnotherCompany() {
        doThrow(new ForbiddenException("You cannot act in that company."))
                .when(scopeGuard).assertCanActIn(any(), eq(2L));

        assertThatThrownBy(() -> service.listCashTills(2L)).isInstanceOf(ForbiddenException.class);
    }
}
