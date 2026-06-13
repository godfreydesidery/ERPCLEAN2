package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.AddOpportunityLineRequest;
import com.erp.modules.crm.domain.dto.AdvanceStageRequest;
import com.erp.modules.crm.domain.dto.CreateOpportunityRequest;
import com.erp.modules.crm.domain.dto.LoseOpportunityRequest;
import com.erp.modules.crm.domain.dto.OpportunityDto;
import com.erp.modules.crm.domain.dto.OpportunityLineDto;
import com.erp.modules.crm.domain.dto.UpdateOpportunityRequest;
import com.erp.modules.crm.domain.dto.WinOpportunityRequest;
import com.erp.modules.crm.domain.entity.Lead;
import com.erp.modules.crm.domain.entity.Opportunity;
import com.erp.modules.crm.domain.entity.OpportunityLine;
import com.erp.modules.crm.domain.entity.PipelineStage;
import com.erp.modules.crm.domain.enums.LeadStatus;
import com.erp.modules.crm.domain.enums.OpportunityStatus;
import com.erp.modules.crm.repository.LeadRepository;
import com.erp.modules.crm.repository.OpportunityLineRepository;
import com.erp.modules.crm.repository.OpportunityRepository;
import com.erp.modules.crm.repository.PipelineStageRepository;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OpportunityServiceImpl implements OpportunityService {

    private final OpportunityRepository opportunities;
    private final OpportunityLineRepository lines;
    private final PipelineStageRepository stages;
    private final LeadRepository leads;
    private final CustomerRepository customers;
    private final AgentRepository agents;
    private final ProductRepository products;
    private final UnitOfMeasureRepository units;
    private final CrmNumberGenerator numberGen;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public OpportunityServiceImpl(OpportunityRepository opportunities,
                                  OpportunityLineRepository lines,
                                  PipelineStageRepository stages,
                                  LeadRepository leads,
                                  CustomerRepository customers,
                                  AgentRepository agents,
                                  ProductRepository products,
                                  UnitOfMeasureRepository units,
                                  CrmNumberGenerator numberGen,
                                  ScopeGuard scopeGuard,
                                  AuditService audit) {
        this.opportunities = opportunities;
        this.lines = lines;
        this.stages = stages;
        this.leads = leads;
        this.customers = customers;
        this.agents = agents;
        this.products = products;
        this.units = units;
        this.numberGen = numberGen;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public OpportunityDto create(CreateOpportunityRequest req) {
        Long companyId = req.companyId();
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);
        Long branchId = requireBranchId(ctx);

        Customer customer = resolveCustomer(companyId, req.customerUid());
        PipelineStage stage = resolveStage(companyId, req.pipelineStageUid());
        Long agentId = resolveAgentId(companyId, req.agentUid());

        BigDecimal prob = req.winProbability() != null ? req.winProbability() : stage.getDefaultProbability();

        Opportunity opp = new Opportunity(companyId, branchId, req.title(),
                customer.getId(), customer.getUid(), req.currency(),
                stage.getId(), prob, actorId());
        opp.setEstimatedValueAmount(req.estimatedValueAmount() != null ? req.estimatedValueAmount() : BigDecimal.ZERO);
        opp.setExpectedCloseDate(req.expectedCloseDate());
        opp.setAgentId(agentId);
        opp.setOwnerUserId(req.ownerUserId());

        // Wire to source lead (if any) and mark lead CONVERTED
        if (req.sourceLeadUid() != null && !req.sourceLeadUid().isBlank()) {
            Lead lead = leads.findByUid(req.sourceLeadUid())
                    .orElseThrow(() -> new NotFoundException("Lead not found: " + req.sourceLeadUid()));
            if (lead.getLeadStatus() != LeadStatus.QUALIFIED) {
                throw new IllegalStateException("Source lead must be QUALIFIED to create an opportunity; current: "
                        + lead.getLeadStatus());
            }
            scopeGuard.assertCanActIn(ctx, lead.getCompanyId());
            opp.setSourceLeadId(lead.getId());
            opp.setSourceLeadUid(lead.getUid());
            // Mark lead CONVERTED (FR-CRM-04)
            lead.setLeadStatus(LeadStatus.CONVERTED);
            lead.setConvertedAt(Instant.now());
            lead.setUpdatedAt(Instant.now());
            lead.setUpdatedBy(actorId());
        }

        // Assign the opportunity number BEFORE save — opportunity_number is NOT NULL, and an autoflush
        // (e.g. the audit query below) would otherwise attempt the INSERT with a null number first.
        String number = numberGen.nextOpportunity(companyId);
        opp.setOpportunityNumber(number);
        Opportunity saved = opportunities.save(opp);

        audit.record(AuditEvent.of(AuditActions.CRM_OPPORTUNITY_CREATE, "opportunities",
                saved.getId(), saved.getUid())
                .detail(Map.of("opportunityNumber", number, "customerUid", req.customerUid())));
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public OpportunityDto getByUid(String uid) {
        Opportunity opp = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), opp.getCompanyId());
        return toDto(opp);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OpportunityDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return opportunities.findByCompanyId(companyId, pageable)
                .map(o -> toDto(o));
    }

    @Override
    public OpportunityDto updateByUid(String uid, UpdateOpportunityRequest req) {
        Opportunity opp = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), opp.getCompanyId());
        assertOpen(opp);

        opp.setTitle(req.title());
        if (req.estimatedValueAmount() != null) opp.setEstimatedValueAmount(req.estimatedValueAmount());
        if (req.winProbability() != null) opp.setWinProbability(req.winProbability());
        opp.setExpectedCloseDate(req.expectedCloseDate());
        if (req.agentUid() != null) opp.setAgentId(resolveAgentId(opp.getCompanyId(), req.agentUid()));
        opp.setOwnerUserId(req.ownerUserId());
        opp.setUpdatedAt(Instant.now());
        opp.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CRM_OPPORTUNITY_UPDATE, "opportunities",
                opp.getId(), opp.getUid()).detail(Map.of()));
        return toDto(opp);
    }

    @Override
    public OpportunityLineDto addLine(String opportunityUid, AddOpportunityLineRequest req) {
        Opportunity opp = require(opportunityUid);
        scopeGuard.assertCanActIn(RequestContext.get(), opp.getCompanyId());
        assertOpen(opp);

        Product product = products.findByCompanyIdAndUid(opp.getCompanyId(), req.productUid())
                .orElseThrow(() -> new NotFoundException("Product not found: " + req.productUid()));
        UnitOfMeasure unit = units.findByCompanyIdAndUid(opp.getCompanyId(), req.unitUid())
                .orElseThrow(() -> new NotFoundException("UnitOfMeasure not found: " + req.unitUid()));

        short lineNo = (short) (lines.findMaxLineNo(opp.getId()) + 1);
        BigDecimal price = req.estimatedUnitPriceAmount() != null ? req.estimatedUnitPriceAmount() : BigDecimal.ZERO;

        OpportunityLine line = new OpportunityLine(opp.getId(), opp.getCompanyId(), opp.getBranchId(),
                lineNo, product.getId(), product.getCode(), product.getName(),
                unit.getId(), unit.getName(), req.estimatedQty(), price, opp.getCurrency(), actorId());
        line.setLineDiscountAmount(req.lineDiscountAmount());
        line.setLineDiscountPercent(req.lineDiscountPercent());

        OpportunityLine saved = lines.save(line);
        audit.record(AuditEvent.of(AuditActions.CRM_OPPORTUNITY_LINE_ADD, "opportunity_lines",
                saved.getId(), saved.getUid())
                .detail(Map.of("opportunityUid", opportunityUid, "productUid", req.productUid())));
        return OpportunityLineDto.from(saved);
    }

    @Override
    public void removeLine(String opportunityUid, String lineUid) {
        Opportunity opp = require(opportunityUid);
        scopeGuard.assertCanActIn(RequestContext.get(), opp.getCompanyId());
        assertOpen(opp);
        OpportunityLine line = lines.findByUidAndOpportunityId(lineUid, opp.getId())
                .orElseThrow(() -> new NotFoundException("OpportunityLine not found: " + lineUid));
        lines.delete(line);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpportunityLineDto> listLines(String opportunityUid) {
        Opportunity opp = require(opportunityUid);
        scopeGuard.assertCanActIn(RequestContext.get(), opp.getCompanyId());
        return lines.findByOpportunityIdOrderByLineNo(opp.getId())
                .stream().map(OpportunityLineDto::from).toList();
    }

    @Override
    public OpportunityDto advanceStage(String uid, AdvanceStageRequest req) {
        Opportunity opp = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), opp.getCompanyId());
        assertOpen(opp);

        PipelineStage stage = resolveStage(opp.getCompanyId(), req.pipelineStageUid());
        opp.setPipelineStageId(stage.getId());
        // Probability follows stage default unless overridden
        opp.setWinProbability(req.winProbabilityOverride() != null
                ? req.winProbabilityOverride()
                : stage.getDefaultProbability());
        opp.setUpdatedAt(Instant.now());
        opp.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CRM_OPPORTUNITY_STAGE_ADVANCE, "opportunities",
                opp.getId(), opp.getUid())
                .detail(Map.of("stageUid", req.pipelineStageUid(), "stageName", stage.getName())));
        return toDto(opp);
    }

    @Override
    public OpportunityDto win(String uid, WinOpportunityRequest req) {
        Opportunity opp = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), opp.getCompanyId());
        if (opp.getOpportunityStatus() != OpportunityStatus.OPEN) {
            throw new IllegalStateException("Only OPEN opportunities can be won; current: " + opp.getOpportunityStatus());
        }
        opp.setOpportunityStatus(OpportunityStatus.WON);
        opp.setWonAt(req.wonAt() != null ? req.wonAt() : Instant.now());
        opp.setUpdatedAt(Instant.now());
        opp.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CRM_OPPORTUNITY_WIN, "opportunities",
                opp.getId(), opp.getUid()).detail(Map.of()));
        return toDto(opp);
    }

    @Override
    public OpportunityDto lose(String uid, LoseOpportunityRequest req) {
        Opportunity opp = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), opp.getCompanyId());
        if (opp.getOpportunityStatus() != OpportunityStatus.OPEN) {
            throw new IllegalStateException("Only OPEN opportunities can be lost; current: " + opp.getOpportunityStatus());
        }
        opp.setOpportunityStatus(OpportunityStatus.LOST);
        opp.setLostAt(Instant.now());
        opp.setLossReason(req.lossReason());
        opp.setUpdatedAt(Instant.now());
        opp.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CRM_OPPORTUNITY_LOSE, "opportunities",
                opp.getId(), opp.getUid())
                .detail(Map.of("lossReason", req.lossReason())));
        return toDto(opp);
    }

    // -------------------------------------------------------------------------

    Opportunity findByUidInternal(String uid) {
        return require(uid);
    }

    void stampConverted(Opportunity opp, String kind, String docUid) {
        opp.setConvertedDocumentKind(kind);
        opp.setConvertedDocumentUid(docUid);
        opp.setConvertedAt(Instant.now());
        opp.setUpdatedAt(Instant.now());
        opp.setUpdatedBy(actorId());
    }

    // -------------------------------------------------------------------------

    private Opportunity require(String uid) {
        return Lookups.orNotFound(opportunities.findByUid(uid), "Opportunity", uid);
    }

    private void assertOpen(Opportunity opp) {
        if (opp.getOpportunityStatus() != OpportunityStatus.OPEN) {
            throw new IllegalStateException(
                    "Cannot modify a closed opportunity; status: " + opp.getOpportunityStatus());
        }
    }

    private Customer resolveCustomer(Long companyId, String uid) {
        return customers.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new NotFoundException("Customer not found: " + uid));
    }

    private PipelineStage resolveStage(Long companyId, String uid) {
        PipelineStage stage = Lookups.orNotFound(stages.findByUid(uid), "PipelineStage", uid);
        if (!stage.getCompanyId().equals(companyId)) {
            throw new IllegalArgumentException("Pipeline stage does not belong to this company: " + uid);
        }
        if (!stage.isActive()) {
            throw new IllegalArgumentException("Pipeline stage is deactivated: " + uid);
        }
        return stage;
    }

    private Long resolveAgentId(Long companyId, String agentUid) {
        if (agentUid == null || agentUid.isBlank()) return null;
        Agent agent = agents.findByCompanyIdAndUid(companyId, agentUid)
                .orElseThrow(() -> new NotFoundException("Agent not found: " + agentUid));
        return agent.getId();
    }

    private OpportunityDto toDto(Opportunity opp) {
        List<OpportunityLineDto> lineList = lines.findByOpportunityIdOrderByLineNo(opp.getId())
                .stream().map(OpportunityLineDto::from).toList();
        return OpportunityDto.from(opp, lineList);
    }

    private Long requireBranchId(RequestContext.Principal ctx) {
        if (ctx == null || ctx.branchId() == null) {
            throw new IllegalStateException("No active branch in context.");
        }
        return ctx.branchId();
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
