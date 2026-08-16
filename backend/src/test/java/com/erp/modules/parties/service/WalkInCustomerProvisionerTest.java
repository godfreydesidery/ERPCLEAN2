package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.CreditStatus;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
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

/** Unit test for {@link WalkInCustomerProvisioner} — no Docker, no Spring context. */
@ExtendWith(MockitoExtension.class)
class WalkInCustomerProvisionerTest {

    @Mock CustomerRepository customers;
    @Mock PartyCodeGenerator codeGenerator;

    @InjectMocks WalkInCustomerProvisioner provisioner;

    @Test
    @DisplayName("the supplied name becomes a CASH_WALK_IN individual with a generated code")
    void createsTheCounterCustomerFromTheSuppliedName() {
        when(codeGenerator.next(9L, "CUSTOMER")).thenReturn("CUST-0001");

        provisioner.createIfNamed(9L, "  Walk-in Customer  ");

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(customers).save(captor.capture());
        Customer saved = captor.getValue();

        assertThat(saved.getCompanyId()).isEqualTo(9L);
        assertThat(saved.getCode()).isEqualTo("CUST-0001");
        assertThat(saved.getDisplayName()).isEqualTo("Walk-in Customer");
        // Entailed, not chosen: BR-PARTY-04 refuses a BUSINESS party without a TIN, and a walk-in
        // has none.
        assertThat(saved.getPartyType()).isEqualTo(PartyType.INDIVIDUAL);
        // Entailed too: this is what settles a POS sale as cash rather than raising an AR open item.
        assertThat(saved.getCustomerKind()).isEqualTo(CustomerKind.CASH_WALK_IN);
        // Asserted explicitly because a half-set Money pair violates chk_customer_credit_pair, and
        // that failure would surface only at the database, inside tenant creation.
        assertThat(saved.getCreditLimit()).isNull();
        assertThat(saved.getCreditStatus()).isEqualTo(CreditStatus.OK);
    }

    @ParameterizedTest(name = "a blank name [{0}] creates no customer and burns no code")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aBlankNameCreatesNothing(String blank) {
        assertThat(provisioner.createIfNamed(9L, blank)).isNull();

        // The code generator matters as much as the repository here: allocating CUST-0001 for a
        // customer that is never created would leave the tenant's first real customer as CUST-0002.
        verifyNoInteractions(customers, codeGenerator);
    }
}
