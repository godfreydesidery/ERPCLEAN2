package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.purchases.domain.dto.CreateLandedCostRequest;
import com.erp.modules.purchases.domain.dto.LandedCostAllocatedPayload;
import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.purchases.domain.entity.LandedCost;
import com.erp.modules.purchases.domain.entity.LandedCostReceipt;
import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
import com.erp.modules.purchases.domain.enums.LandedCostBasis;
import com.erp.modules.purchases.domain.enums.LandedCostChargeType;
import com.erp.modules.purchases.domain.enums.LandedCostStatus;
import com.erp.modules.purchases.repository.GoodsReceiptLineRepository;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.LandedCostAllocationRepository;
import com.erp.modules.purchases.repository.LandedCostChargeRepository;
import com.erp.modules.purchases.repository.LandedCostReceiptRepository;
import com.erp.modules.purchases.repository.LandedCostRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Landed costs are capitalised into the stock of the branch that RECEIVED the goods (PUR-09), and
 * only onto receipts that still stand (PUR-28) — 2026-10-10 adversarial review.
 */
class LandedCostServiceImplTest {

    private LandedCostRepository        landedCosts;
    private LandedCostReceiptRepository lcReceipts;
    private GoodsReceiptRepository      grRepo;
    private GoodsReceiptLineRepository  grLineRepo;
    private OutboxPublisher             outbox;
    private LandedCostServiceImpl       service;

    @BeforeEach
    void setUp() {
        landedCosts = mock(LandedCostRepository.class);
        lcReceipts  = mock(LandedCostReceiptRepository.class);
        grRepo      = mock(GoodsReceiptRepository.class);
        grLineRepo  = mock(GoodsReceiptLineRepository.class);
        outbox      = mock(OutboxPublisher.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        PurchaseNumberGenerator numbers = mock(PurchaseNumberGenerator.class);
        service = new LandedCostServiceImpl(landedCosts, lcReceipts,
                mock(LandedCostChargeRepository.class), mock(LandedCostAllocationRepository.class),
                grRepo, grLineRepo, companies, numbers, outbox, mock(ScopeGuard.class),
                mock(AuditService.class));

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(10L);
        when(companies.findByUid("COMP")).thenReturn(Optional.of(company));
        when(numbers.nextLandedCost(10L)).thenReturn("LC-0001");
        when(landedCosts.save(any(LandedCost.class))).thenAnswer(inv -> inv.getArgument(0));

        // The user is working in branch 77.
        RequestContext.set(new RequestContext.Principal(1L, "u@test", false, 10L, 77L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void create_recordsTheLandedCostAtTheReceivingBranch() {
        receipt("GR-A", 20L, GoodsReceiptStatus.RECEIVED);

        service.create(request("GR-A"));

        ArgumentCaptor<LandedCost> saved = ArgumentCaptor.forClass(LandedCost.class);
        verify(landedCosts).save(saved.capture());
        assertThat(saved.getValue().getBranchId()).as("receipt's branch, not the user's 77").isEqualTo(20L);
        ArgumentCaptor<LandedCostReceipt> link = ArgumentCaptor.forClass(LandedCostReceipt.class);
        verify(lcReceipts).save(link.capture());
        assertThat(link.getValue().getBranchId()).isEqualTo(20L);
    }

    @Test
    void create_withReceiptsFromTwoBranches_isRefusedBeforeAnythingIsWritten() {
        receipt("GR-A", 20L, GoodsReceiptStatus.RECEIVED);
        receipt("GR-B", 21L, GoodsReceiptStatus.RECEIVED);

        assertThatThrownBy(() -> service.create(request("GR-A", "GR-B")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different branches");
        verify(landedCosts, never()).save(any());
    }

    @Test
    void create_onAVoidedReceipt_isRefused() {
        receipt("GR-V", 20L, GoodsReceiptStatus.VOID);

        assertThatThrownBy(() -> service.create(request("GR-V")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has been voided");
        verify(landedCosts, never()).save(any());
    }

    @Test
    void confirm_allocatesAtTheReceivingBranchEvenForAnOldDraftRaisedElsewhere() {
        LandedCost lc = mock(LandedCost.class);
        when(lc.getId()).thenReturn(5L);
        when(lc.getUid()).thenReturn("LC-UID");
        when(lc.getCompanyId()).thenReturn(10L);
        when(lc.getBranchId()).thenReturn(77L);   // raised from the wrong branch before the fix
        when(lc.getStatus()).thenReturn(LandedCostStatus.DRAFT);
        when(lc.getBasis()).thenReturn(LandedCostBasis.BY_VALUE);
        when(lc.getTotalChargeAmount()).thenReturn(new BigDecimal("1000"));
        when(landedCosts.findByUid("LC-UID")).thenReturn(Optional.of(lc));

        LandedCostReceipt link = mock(LandedCostReceipt.class);
        when(link.getGoodsReceiptId()).thenReturn(40L);
        when(lcReceipts.findByLandedCostId(5L)).thenReturn(List.of(link));
        GoodsReceipt gr = mock(GoodsReceipt.class);
        when(gr.getStatus()).thenReturn(GoodsReceiptStatus.RECEIVED);
        GoodsReceiptLine line = mock(GoodsReceiptLine.class);
        when(line.getGoodsReceipt()).thenReturn(gr);
        when(line.getBranchId()).thenReturn(20L);
        when(line.getLineCostAmount()).thenReturn(new BigDecimal("5000"));
        when(grLineRepo.findByGoodsReceiptIdOrderByLineNo(40L)).thenReturn(List.of(line));

        service.confirm("LC-UID");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(any(), any(), any(), any(), any(), eq(20L), payload.capture());
        assertThat(((LandedCostAllocatedPayload) payload.getValue()).branchId()).isEqualTo(20L);
    }

    private void receipt(String uid, Long branchId, GoodsReceiptStatus status) {
        GoodsReceipt gr = mock(GoodsReceipt.class);
        when(gr.getId()).thenReturn((long) uid.hashCode());
        when(gr.getUid()).thenReturn(uid);
        when(gr.getReceiptNumber()).thenReturn("GRN-" + uid);
        when(gr.getBranchId()).thenReturn(branchId);
        when(gr.getStatus()).thenReturn(status);
        when(grRepo.findByCompanyIdAndUid(10L, uid)).thenReturn(Optional.of(gr));
    }

    private static CreateLandedCostRequest request(String... receiptUids) {
        return new CreateLandedCostRequest("COMP", LandedCostBasis.BY_VALUE, null,
                List.of(receiptUids),
                List.of(new CreateLandedCostRequest.ChargeRequest(
                        LandedCostChargeType.FREIGHT, new BigDecimal("1000"))));
    }
}
