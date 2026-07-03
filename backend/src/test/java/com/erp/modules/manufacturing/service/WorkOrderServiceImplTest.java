package com.erp.modules.manufacturing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.manufacturing.domain.dto.WorkOrderDto;
import com.erp.modules.manufacturing.domain.entity.WorkOrder;
import com.erp.modules.manufacturing.repository.WorkOrderComponentRepository;
import com.erp.modules.manufacturing.repository.WorkOrderOperationRepository;
import com.erp.modules.manufacturing.repository.WorkOrderRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link WorkOrderServiceImpl} branch name enrichment.
 *
 * <p>A branch manager could not see WHICH branch a work order belonged to — only the internal
 * numeric {@code branchId} travelled in the DTO. Fixed by resolving branchName/branchCode at read
 * time via {@link BranchRepository}, mirroring {@code SalesOrderServiceImpl.buildDto}.
 *
 * <p>Only the enrichment path is exercised here (getByUid + list); the rest of
 * {@link WorkOrderServiceImpl} is covered by {@link WorkOrderServiceImplIT}.
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderServiceImplTest {

    @Mock WorkOrderRepository workOrders;
    @Mock WorkOrderComponentRepository components;
    @Mock WorkOrderOperationRepository operations;
    @Mock com.erp.modules.products.repository.ProductRepository products;
    @Mock com.erp.modules.products.repository.BomRepository boms;
    @Mock BranchRepository branches;
    @Mock com.erp.modules.costing.repository.DimensionValueRepository dimensionValues;
    @Mock WorkOrderNumberGenerator numberGenerator;
    @Mock ScopeGuard scopeGuard;
    @Mock com.erp.platform.audit.AuditService audit;
    @Mock com.erp.platform.events.OutboxPublisher outbox;

    @InjectMocks WorkOrderServiceImpl service;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID  = 10L;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void getByUid_resolvesBranchNameAndCode() {
        WorkOrder wo = workOrderWithId(900L, "WOUID00000000000000000001");
        when(workOrders.findByUid("WOUID00000000000000000001")).thenReturn(Optional.of(wo));
        when(components.findByWorkOrderIdOrderByLineNoAsc(900L)).thenReturn(List.of());
        when(operations.findByWorkOrderIdOrderBySeqNoAsc(900L)).thenReturn(List.of());
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(branch("BR-01", "Head Office")));

        WorkOrderDto dto = service.getByUid("WOUID00000000000000000001");

        assertThat(dto.branchId()).isEqualTo(BRANCH_ID);
        assertThat(dto.branchName()).isEqualTo("Head Office");
        assertThat(dto.branchCode()).isEqualTo("BR-01");
    }

    @Test
    void getByUid_branchNamesNull_whenBranchRowMissing() {
        WorkOrder wo = workOrderWithId(901L, "WOUID00000000000000000002");
        when(workOrders.findByUid("WOUID00000000000000000002")).thenReturn(Optional.of(wo));
        when(components.findByWorkOrderIdOrderByLineNoAsc(901L)).thenReturn(List.of());
        when(operations.findByWorkOrderIdOrderBySeqNoAsc(901L)).thenReturn(List.of());
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.empty());

        WorkOrderDto dto = service.getByUid("WOUID00000000000000000002");

        assertThat(dto.branchName()).isNull();
        assertThat(dto.branchCode()).isNull();
        // never throws — a missing branch row degrades to null names, it never fails the read.
    }

    @Test
    void list_resolvesBranchNameForEveryRow() {
        WorkOrder wo1 = workOrderWithId(902L, "WOUID00000000000000000003");
        WorkOrder wo2 = workOrderWithId(903L, "WOUID00000000000000000004");
        when(workOrders.findByCompanyId(COMPANY_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(wo1, wo2)));
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(branch("BR-01", "Head Office")));

        Page<WorkOrderDto> page = service.list(COMPANY_ID, null, Pageable.unpaged());

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).branchName()).isEqualTo("Head Office");
        assertThat(page.getContent().get(1).branchName()).isEqualTo("Head Office");
        // list rows are resolved one findById per row (no batch fetch) — same N+1 shape as
        // SalesOrderServiceImpl.list/buildDto; acceptable per the existing module style.
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static WorkOrder workOrderWithId(Long id, String uid) {
        WorkOrder wo = new WorkOrder(COMPANY_ID, BRANCH_ID, 100L, "FG-001", "Finished Good",
                BigDecimal.TEN, "WO-0001");
        wo.setCreatedBy(1L);
        ReflectionTestUtils.setField(wo, "id", id);
        ReflectionTestUtils.setField(wo, "uid", uid);
        return wo;
    }

    /** Company param intentionally null — only name/code are read by the enrichment path. */
    private static Branch branch(String code, String name) {
        return new Branch(null, code, name);
    }
}
