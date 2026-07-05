package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.SubmitForApprovalRequest;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.modules.approvals.service.ApprovalEngine;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link SalesApprovalGate} (deferred item D-4 — SO amount-threshold approval,
 * extending the PR #189 engine-derived approval flow). Mirrors the intent of PoApprovalGate.
 */
@ExtendWith(MockitoExtension.class)
class SalesApprovalGateTest {

    @Mock SalesSettingsRepository settings;
    @Mock ApprovalEngine approvalEngine;
    @Mock CustomerRepository customers;

    @InjectMocks SalesApprovalGate gate;

    private static final Long COMPANY_ID  = 1L;
    private static final Long CUSTOMER_ID = 200L;
    private static final String BRANCH_UID = "BRUID0000000000000000001";

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void requiresApproval_belowThreshold_returnsFalse() {
        SalesOrder order = orderWithTotal(BigDecimal.valueOf(500));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(
                Optional.of(settings(true, BigDecimal.valueOf(1000))));

        assertThat(gate.requiresApproval(order, BRANCH_UID)).isFalse();
    }

    @Test
    void requiresApproval_atThreshold_returnsTrue() {
        SalesOrder order = orderWithTotal(BigDecimal.valueOf(1000));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(
                Optional.of(settings(true, BigDecimal.valueOf(1000))));

        assertThat(gate.requiresApproval(order, BRANCH_UID)).isTrue();
    }

    @Test
    void requiresApproval_aboveThreshold_returnsTrue() {
        SalesOrder order = orderWithTotal(BigDecimal.valueOf(5000));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(
                Optional.of(settings(true, BigDecimal.valueOf(1000))));

        assertThat(gate.requiresApproval(order, BRANCH_UID)).isTrue();
    }

    @Test
    void requiresApproval_gateDisabled_returnsFalse() {
        SalesOrder order = orderWithTotal(BigDecimal.valueOf(999999));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(
                Optional.of(settings(false, BigDecimal.valueOf(1000))));

        assertThat(gate.requiresApproval(order, BRANCH_UID)).isFalse();
    }

    @Test
    void requiresApproval_noSettingsRow_returnsFalse() {
        SalesOrder order = orderWithTotal(BigDecimal.valueOf(999999));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());

        assertThat(gate.requiresApproval(order, BRANCH_UID)).isFalse();
    }

    @Test
    void requiresApproval_nullThresholdEnabled_alwaysTrue() {
        SalesOrder order = orderWithTotal(BigDecimal.ZERO);
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(
                Optional.of(settings(true, null)));

        assertThat(gate.requiresApproval(order, BRANCH_UID)).isTrue();
    }

    @Test
    void submit_buildsRequestWithDocumentTypeAndCustomerNameSummary() {
        SalesOrder order = orderWithTotal(BigDecimal.valueOf(5000));
        when(customers.findByCompanyIdAndId(COMPANY_ID, CUSTOMER_ID)).thenReturn(
                Optional.of(customer(CUSTOMER_ID, "Acme Traders")));
        when(approvalEngine.submitForApproval(any())).thenReturn(
                approvalRequest(order.getUid(), ApprovalRequestStatus.PENDING));

        ApprovalRequestDto result = gate.submit(order, BRANCH_UID);

        ArgumentCaptor<SubmitForApprovalRequest> captor =
                ArgumentCaptor.forClass(SubmitForApprovalRequest.class);
        org.mockito.Mockito.verify(approvalEngine).submitForApproval(captor.capture());
        SubmitForApprovalRequest req = captor.getValue();
        assertThat(req.documentType()).isEqualTo("SALES_ORDER");
        assertThat(req.documentUid()).isEqualTo(order.getUid());
        assertThat(req.companyId()).isEqualTo(COMPANY_ID);
        assertThat(req.branchUid()).isEqualTo(BRANCH_UID);
        assertThat(req.amount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(req.summary()).contains("Acme Traders");
        assertThat(result.status()).isEqualTo(ApprovalRequestStatus.PENDING);
    }

    @Test
    void submit_customerRowMissing_summaryOmitsName() {
        SalesOrder order = orderWithTotal(BigDecimal.valueOf(5000));
        when(customers.findByCompanyIdAndId(COMPANY_ID, CUSTOMER_ID)).thenReturn(Optional.empty());
        when(approvalEngine.submitForApproval(any())).thenReturn(
                approvalRequest(order.getUid(), ApprovalRequestStatus.APPROVED));

        ApprovalRequestDto result = gate.submit(order, BRANCH_UID);

        assertThat(result.status()).isEqualTo(ApprovalRequestStatus.APPROVED);
        ArgumentCaptor<SubmitForApprovalRequest> captor =
                ArgumentCaptor.forClass(SubmitForApprovalRequest.class);
        org.mockito.Mockito.verify(approvalEngine).submitForApproval(captor.capture());
        assertThat(captor.getValue().summary()).doesNotContain("—");
    }

    @Test
    void submitIndependent_delegatesToSubmit_persistingViaTheSameEngineCall() {
        // persona UAT I3: submitIndependent is the REQUIRES_NEW entry point SalesOrderServiceImpl
        // now calls; here (without a real Spring transaction manager in a plain unit test) it must
        // still behave exactly like submit() — same engine call, same result passed through.
        SalesOrder order = orderWithTotal(BigDecimal.valueOf(5000));
        when(customers.findByCompanyIdAndId(COMPANY_ID, CUSTOMER_ID)).thenReturn(
                Optional.of(customer(CUSTOMER_ID, "Acme Traders")));
        when(approvalEngine.submitForApproval(any())).thenReturn(
                approvalRequest(order.getUid(), ApprovalRequestStatus.PENDING));

        ApprovalRequestDto result = gate.submitIndependent(order, BRANCH_UID);

        assertThat(result.status()).isEqualTo(ApprovalRequestStatus.PENDING);
        org.mockito.Mockito.verify(approvalEngine).submitForApproval(any());
    }

    @Test
    void submitIndependent_noMatchingPolicy_throwsApprovalPolicyMissingException() {
        // R1: the engine auto-approved because no SALES_ORDER policy matched — submitIndependent
        // must detect this and throw INSTEAD of returning the auto-approved result, so its own
        // REQUIRES_NEW transaction rolls back and the misconfigured "approval" is never persisted
        // durably (see ApprovalPolicyMissingException javadoc).
        SalesOrder order = orderWithTotal(BigDecimal.valueOf(5000));
        when(customers.findByCompanyIdAndId(COMPANY_ID, CUSTOMER_ID)).thenReturn(
                Optional.of(customer(CUSTOMER_ID, "Acme Traders")));
        when(approvalEngine.submitForApproval(any())).thenReturn(
                approvalRequest(order.getUid(), ApprovalRequestStatus.APPROVED, true));

        assertThatThrownBy(() -> gate.submitIndependent(order, BRANCH_UID))
                .isInstanceOf(ApprovalPolicyMissingException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SalesOrder orderWithTotal(BigDecimal grossTotal) {
        SalesOrder order = new SalesOrder(COMPANY_ID, 10L, CUSTOMER_ID, null,
                "TZS", LocalDate.now(), 1L);
        ReflectionTestUtils.setField(order, "id", 500L);
        ReflectionTestUtils.setField(order, "uid", "SOUID000000000000000099");
        order.setGrossTotalAmount(grossTotal);
        order.setOrderNumber("SO-500");
        return order;
    }

    private static SalesSettings settings(boolean enabled, BigDecimal threshold) {
        SalesSettings s = new SalesSettings(COMPANY_ID, 1L);
        s.setSoApprovalEnabled(enabled);
        s.setSoApprovalThresholdAmount(threshold);
        return s;
    }

    private static Customer customer(Long id, String displayName) {
        Customer c = new Customer(COMPANY_ID, "CUST-0001", PartyType.INDIVIDUAL, displayName,
                CustomerKind.CREDIT_ACCOUNT, 1L);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private static ApprovalRequestDto approvalRequest(String documentUid, ApprovalRequestStatus status) {
        return approvalRequest(documentUid, status, status == ApprovalRequestStatus.APPROVED);
    }

    private static ApprovalRequestDto approvalRequest(String documentUid, ApprovalRequestStatus status,
                                                       boolean autoApproved) {
        return new ApprovalRequestDto(
                1L, "APRUID00000000000000001", COMPANY_ID, 10L, null, null,
                "APR-0001", "SALES_ORDER", documentUid, BigDecimal.TEN, "TZS",
                status, null, autoApproved,
                null, null, "summary",
                1L, null, java.time.Instant.now(), null, null, null, List.of());
    }
}
