package com.erp.modules.crm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.crm.domain.dto.NewCustomerDetailsDto;
import com.erp.modules.crm.domain.dto.QualifyLeadRequest;
import com.erp.modules.crm.domain.dto.UpdateLeadRequest;
import com.erp.modules.crm.domain.entity.Lead;
import com.erp.modules.crm.domain.enums.LeadSource;
import com.erp.modules.crm.domain.enums.LeadStatus;
import com.erp.modules.crm.repository.LeadRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.service.CustomerService;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link LeadServiceImpl} — covers the four CRM defects fixed in 2026-06-21.
 *
 * <ul>
 *   <li>crm-062 (High)    — qualify with INDIVIDUAL partyType does not force BUSINESS / TIN path.</li>
 *   <li>crm re-qualify    — re-qualifying an already-QUALIFIED lead throws 409 ConflictException.</li>
 *   <li>crm leadSource    — PUT update applies the leadSource field from the request.</li>
 *   <li>crm win @Valid    — OpportunityController adds @Valid (controller-layer; service-side wonAt
 *                           no longer has a null fallback — tested via OpportunityServiceImplTest).</li>
 * </ul>
 */
class LeadServiceImplTest {

    // -------------------------------------------------------------------------
    // Collaborator mocks
    // -------------------------------------------------------------------------

    private LeadRepository      leadRepo;
    private CustomerRepository  customerRepo;
    private CustomerService     customerService;
    private CompanyRepository   companies;
    private CrmNumberGenerator  numberGen;
    private ScopeGuard          scopeGuard;
    private AuditService        audit;

    private LeadServiceImpl service;

    @BeforeEach
    void setUp() {
        leadRepo        = mock(LeadRepository.class);
        customerRepo    = mock(CustomerRepository.class);
        customerService = mock(CustomerService.class);
        companies       = mock(CompanyRepository.class);
        numberGen       = mock(CrmNumberGenerator.class);
        scopeGuard      = mock(ScopeGuard.class);
        audit           = mock(AuditService.class);

        service = new LeadServiceImpl(
                leadRepo, customerRepo, customerService, companies, numberGen, scopeGuard, audit);

        // Default principal — companyId=10, branchId=20
        RequestContext.set(new RequestContext.Principal(1L, "user@test.com", false, 10L, 20L, null));

        // ScopeGuard passes by default
        doNothing().when(scopeGuard).assertCanActIn(any(), anyLong());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a real Lead (not a mock) with the given uid and status so that
     * field setters exercised by the service actually mutate state.
     */
    private Lead realLead(String uid, LeadStatus status) {
        Lead lead = new Lead(10L, 20L, "Test Lead", LeadSource.WEBSITE, 1L);
        lead.setLeadStatus(status);
        when(leadRepo.findByUid(uid)).thenReturn(Optional.of(lead));
        when(leadRepo.save(any(Lead.class))).thenAnswer(inv -> inv.getArgument(0));
        return lead;
    }

    // -------------------------------------------------------------------------
    // crm-062: qualify with INDIVIDUAL partyType passes INDIVIDUAL to CustomerService
    // -------------------------------------------------------------------------

    @Test
    void qualify_withIndividualPartyType_passesThroughToCustomerService() {
        realLead("LEAD-001", LeadStatus.NEW);

        NewCustomerDetailsDto details = new NewCustomerDetailsDto(
                "John Walk-In",
                CustomerKind.CASH_WALK_IN,
                PartyType.INDIVIDUAL,   // explicit — fixes crm-062
                "+255712345678",
                null, null, null, null
        );
        QualifyLeadRequest req = new QualifyLeadRequest(null, details);

        // CustomerService returns a stub DTO
        CustomerDto stub = stubCustomerDto(PartyType.INDIVIDUAL);
        when(customerService.create(any(CreateCustomerRequest.class))).thenReturn(stub);

        service.qualify("LEAD-001", req);

        ArgumentCaptor<CreateCustomerRequest> captor = ArgumentCaptor.forClass(CreateCustomerRequest.class);
        verify(customerService).create(captor.capture());
        assertThat(captor.getValue().partyType()).isEqualTo(PartyType.INDIVIDUAL);
    }

    @Test
    void qualify_withNullPartyType_defaultsToIndividual() {
        realLead("LEAD-002", LeadStatus.NEW);

        // partyType is null — service must default to INDIVIDUAL, not BUSINESS
        NewCustomerDetailsDto details = new NewCustomerDetailsDto(
                "Walk-In Customer",
                CustomerKind.CASH_WALK_IN,
                null,           // null → service must resolve to INDIVIDUAL
                null, null, null, null, null
        );
        QualifyLeadRequest req = new QualifyLeadRequest(null, details);

        CustomerDto stub = stubCustomerDto(PartyType.INDIVIDUAL);
        when(customerService.create(any(CreateCustomerRequest.class))).thenReturn(stub);

        service.qualify("LEAD-002", req);

        ArgumentCaptor<CreateCustomerRequest> captor = ArgumentCaptor.forClass(CreateCustomerRequest.class);
        verify(customerService).create(captor.capture());
        assertThat(captor.getValue().partyType())
                .as("null partyType in request must default to INDIVIDUAL to avoid BR-PARTY-04")
                .isEqualTo(PartyType.INDIVIDUAL);
    }

    @Test
    void qualify_withBusinessPartyType_passesBusiness() {
        realLead("LEAD-003", LeadStatus.NEW);

        NewCustomerDetailsDto details = new NewCustomerDetailsDto(
                "Acme Corp",
                CustomerKind.CREDIT_ACCOUNT,
                PartyType.BUSINESS,
                null, null, null, null, null
        );
        QualifyLeadRequest req = new QualifyLeadRequest(null, details);

        CustomerDto stub = stubCustomerDto(PartyType.BUSINESS);
        when(customerService.create(any(CreateCustomerRequest.class))).thenReturn(stub);

        service.qualify("LEAD-003", req);

        ArgumentCaptor<CreateCustomerRequest> captor = ArgumentCaptor.forClass(CreateCustomerRequest.class);
        verify(customerService).create(captor.capture());
        assertThat(captor.getValue().partyType()).isEqualTo(PartyType.BUSINESS);
    }

    // -------------------------------------------------------------------------
    // crm re-qualify: already-QUALIFIED lead must throw ConflictException (409)
    // -------------------------------------------------------------------------

    @Test
    void qualify_alreadyQualifiedLead_throwsConflict() {
        realLead("LEAD-004", LeadStatus.QUALIFIED);

        QualifyLeadRequest req = new QualifyLeadRequest(null,
                new NewCustomerDetailsDto("X", CustomerKind.CASH_WALK_IN, null, null, null, null, null, null));

        assertThatThrownBy(() -> service.qualify("LEAD-004", req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already QUALIFIED");

        // CustomerService must NOT be called — no duplicate customer creation
        verify(customerService, never()).create(any());
    }

    @Test
    void qualify_convertedLead_throwsIllegalState() {
        realLead("LEAD-005", LeadStatus.CONVERTED);

        QualifyLeadRequest req = new QualifyLeadRequest(null,
                new NewCustomerDetailsDto("X", CustomerKind.CASH_WALK_IN, null, null, null, null, null, null));

        assertThatThrownBy(() -> service.qualify("LEAD-005", req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    // -------------------------------------------------------------------------
    // crm leadSource: updateByUid must apply leadSource from the request
    // -------------------------------------------------------------------------

    @Test
    void update_appliesLeadSourceChange() {
        Lead lead = realLead("LEAD-006", LeadStatus.NEW);
        // Initial source is WEBSITE (set via constructor)
        assertThat(lead.getLeadSource()).isEqualTo(LeadSource.WEBSITE);

        UpdateLeadRequest req = new UpdateLeadRequest(
                "Updated Name",
                LeadSource.REFERRAL,   // new source
                null, null, null, null, null, null
        );

        service.updateByUid("LEAD-006", req);

        assertThat(lead.getLeadSource())
                .as("leadSource must be updated to REFERRAL after PUT")
                .isEqualTo(LeadSource.REFERRAL);
        assertThat(lead.getDisplayName()).isEqualTo("Updated Name");
    }

    @Test
    void update_preservesLeadSourceWhenUnchanged() {
        Lead lead = realLead("LEAD-007", LeadStatus.NEW);

        UpdateLeadRequest req = new UpdateLeadRequest(
                "Same Name",
                LeadSource.WEBSITE,    // unchanged
                null, null, null, null, null, null
        );

        service.updateByUid("LEAD-007", req);

        assertThat(lead.getLeadSource()).isEqualTo(LeadSource.WEBSITE);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private CustomerDto stubCustomerDto(PartyType partyType) {
        return new CustomerDto(
                99L, "CUST-UID-001", 10L, null, partyType,
                "Stub Customer", null, null, false, null, null, null,
                null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null,
                false, null, null, null, null, null,
                com.erp.platform.common.domain.MasterStatus.ACTIVE, null,
                null, 1L, null, null
        );
    }
}
