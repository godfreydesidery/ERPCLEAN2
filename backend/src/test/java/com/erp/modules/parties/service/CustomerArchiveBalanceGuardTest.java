package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.domain.dto.ArUnconvertedAmountDto;
import com.erp.modules.ar.service.ArBalanceService;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerBranchRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ARC-29: a customer who still owes money (or is owed) cannot be archived out of the pickers. */
class CustomerArchiveBalanceGuardTest {

    private CustomerRepository customers;
    private ArBalanceService arBalance;
    private AuditService audit;
    private Customer customer;
    private CustomerServiceImpl service;

    @BeforeEach
    void setUp() {
        customers = mock(CustomerRepository.class);
        arBalance = mock(ArBalanceService.class);
        audit = mock(AuditService.class);
        service = new CustomerServiceImpl(customers, mock(CustomerBranchRepository.class),
                mock(PaymentTermsRepository.class), mock(PriceListRepository.class),
                mock(AgentRepository.class), mock(PartyCodeGenerator.class),
                mock(PartyBranchGuard.class), mock(ScopeGuard.class), audit, arBalance);
        customer = mock(Customer.class);
        when(customer.getId()).thenReturn(7L);
        when(customer.getUid()).thenReturn("KIBO");
        when(customer.getCompanyId()).thenReturn(10L);
        when(customer.getStatus()).thenReturn(MasterStatus.ACTIVE);
        when(customers.findByUid("KIBO")).thenReturn(Optional.of(customer));
    }

    @Test
    void refusedWhileTheCustomerOwesMoney() {
        when(arBalance.currentBalance(10L, 7L))
                .thenReturn(new ArBalanceDto(10L, 7L, new BigDecimal("150000"), "TZS"));
        assertThatThrownBy(() -> service.archiveByUid("KIBO"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("unpaid balance of TZS 150,000.00");
        verify(customer, never()).setStatus(any());
    }

    @Test
    void refusedWhileMoneyIsHeldOnAccountOrOwedInAForeignCurrency() {
        when(arBalance.currentBalance(10L, 7L))
                .thenReturn(new ArBalanceDto(10L, 7L, new BigDecimal("-500"), "TZS"));
        assertThatThrownBy(() -> service.archiveByUid("KIBO"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("money held on account");

        when(arBalance.currentBalance(10L, 7L)).thenReturn(new ArBalanceDto(10L, 7L,
                BigDecimal.ZERO, "TZS", List.of(new ArUnconvertedAmountDto("USD", BigDecimal.TEN, 1))));
        assertThatThrownBy(() -> service.archiveByUid("KIBO"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("foreign currency");
    }

    @Test
    void allowedWhenTheAccountIsSettled() {
        when(arBalance.currentBalance(10L, 7L))
                .thenReturn(new ArBalanceDto(10L, 7L, BigDecimal.ZERO, "TZS"));
        assertThatCode(() -> service.archiveByUid("KIBO")).doesNotThrowAnyException();
        verify(customer).setStatus(MasterStatus.ARCHIVED);
    }
}
