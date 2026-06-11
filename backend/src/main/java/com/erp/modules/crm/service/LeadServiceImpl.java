package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.CreateLeadRequest;
import com.erp.modules.crm.domain.dto.DisqualifyLeadRequest;
import com.erp.modules.crm.domain.dto.LeadDto;
import com.erp.modules.crm.domain.dto.QualifyLeadRequest;
import com.erp.modules.crm.domain.dto.UpdateLeadRequest;
import com.erp.modules.crm.domain.entity.Lead;
import com.erp.modules.crm.domain.enums.LeadStatus;
import com.erp.modules.crm.repository.LeadRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.service.CustomerService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LeadServiceImpl implements LeadService {

    private final LeadRepository leads;
    private final CustomerRepository customers;
    private final CustomerService customerService;
    private final CompanyRepository companies;
    private final CrmNumberGenerator numberGen;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public LeadServiceImpl(LeadRepository leads,
                           CustomerRepository customers,
                           CustomerService customerService,
                           CompanyRepository companies,
                           CrmNumberGenerator numberGen,
                           ScopeGuard scopeGuard,
                           AuditService audit) {
        this.leads = leads;
        this.customers = customers;
        this.customerService = customerService;
        this.companies = companies;
        this.numberGen = numberGen;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public LeadDto create(CreateLeadRequest req) {
        Long companyId = req.companyId();
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);
        Long branchId = requireBranchId(ctx);

        Lead lead = new Lead(companyId, branchId, req.displayName(), req.leadSource(), actorId());
        lead.setCompanyName(req.companyName());
        lead.setContactPerson(req.contactPerson());
        lead.setPhone(req.phone());
        lead.setEmail(req.email());
        lead.setOwnerUserId(req.ownerUserId());
        lead.setNotes(req.notes());

        Lead saved = leads.save(lead);
        // Allocate LEAD-#### at create (D-8)
        String number = numberGen.nextLead(companyId);
        saved.setLeadNumber(number);

        audit.record(AuditEvent.of(AuditActions.CRM_LEAD_CREATE, "leads",
                saved.getId(), saved.getUid())
                .detail(Map.of("leadNumber", number, "displayName", req.displayName())));
        return LeadDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public LeadDto getByUid(String uid) {
        Lead lead = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), lead.getCompanyId());
        return LeadDto.from(lead);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LeadDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return leads.findByCompanyId(companyId, pageable).map(LeadDto::from);
    }

    @Override
    public LeadDto updateByUid(String uid, UpdateLeadRequest req) {
        Lead lead = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), lead.getCompanyId());
        assertEditable(lead);

        lead.setDisplayName(req.displayName());
        lead.setCompanyName(req.companyName());
        lead.setContactPerson(req.contactPerson());
        lead.setPhone(req.phone());
        lead.setEmail(req.email());
        lead.setOwnerUserId(req.ownerUserId());
        lead.setNotes(req.notes());
        lead.setUpdatedAt(Instant.now());
        lead.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CRM_LEAD_UPDATE, "leads",
                lead.getId(), lead.getUid()).detail(Map.of()));
        return LeadDto.from(lead);
    }

    @Override
    public LeadDto contact(String uid) {
        Lead lead = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), lead.getCompanyId());
        if (lead.getLeadStatus() != LeadStatus.NEW) {
            throw new IllegalStateException("Only NEW leads can move to CONTACTED; current: " + lead.getLeadStatus());
        }
        lead.setLeadStatus(LeadStatus.CONTACTED);
        lead.setUpdatedAt(Instant.now());
        lead.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.CRM_LEAD_CONTACT, "leads",
                lead.getId(), lead.getUid()).detail(Map.of()));
        return LeadDto.from(lead);
    }

    @Override
    public LeadDto qualify(String uid, QualifyLeadRequest req) {
        Lead lead = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), lead.getCompanyId());

        if (lead.getLeadStatus() == LeadStatus.CONVERTED || lead.getLeadStatus() == LeadStatus.DISQUALIFIED) {
            throw new IllegalStateException("Cannot qualify a terminal lead; current: " + lead.getLeadStatus());
        }

        Long customerId;
        String customerUid;

        if (req.existingCustomerUid() != null && !req.existingCustomerUid().isBlank()) {
            // Link to existing customer — must belong to the same company
            var customer = customers.findByUid(req.existingCustomerUid())
                    .orElseThrow(() -> new NotFoundException("Customer not found: " + req.existingCustomerUid()));
            scopeGuard.assertCanActIn(RequestContext.get(), customer.getCompanyId());
            customerId = customer.getId();
            customerUid = customer.getUid();
        } else if (req.newCustomerDetails() != null) {
            // Promote lead to a new Parties customer via CustomerService (CRM does not write customers directly)
            var details = req.newCustomerDetails();
            CreateCustomerRequest createReq = new CreateCustomerRequest(
                    lead.getCompanyId(),
                    PartyType.BUSINESS,   // default; override if details had individual flag (v1 uses BUSINESS)
                    details.displayName(),
                    null, null, false, null, null, null,
                    details.phone(), details.email(),
                    details.physicalAddress(), null,
                    details.region(), details.district(),
                    details.customerKind() != null ? details.customerKind() : CustomerKind.CREDIT_ACCOUNT,
                    null, null
            );
            CustomerDto created = customerService.create(createReq);
            customerId = created.id();
            customerUid = created.uid();
        } else {
            throw new IllegalArgumentException("Either existingCustomerUid or newCustomerDetails must be provided.");
        }

        lead.setCustomerId(customerId);
        lead.setCustomerUid(customerUid);
        lead.setLeadStatus(LeadStatus.QUALIFIED);
        lead.setQualifiedAt(Instant.now());
        lead.setUpdatedAt(Instant.now());
        lead.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CRM_LEAD_QUALIFY, "leads",
                lead.getId(), lead.getUid())
                .detail(Map.of("customerUid", customerUid)));
        return LeadDto.from(lead);
    }

    @Override
    public LeadDto disqualify(String uid, DisqualifyLeadRequest req) {
        Lead lead = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), lead.getCompanyId());
        if (lead.getLeadStatus() == LeadStatus.CONVERTED || lead.getLeadStatus() == LeadStatus.DISQUALIFIED) {
            throw new IllegalStateException("Cannot disqualify a terminal lead; current: " + lead.getLeadStatus());
        }
        lead.setLeadStatus(LeadStatus.DISQUALIFIED);
        lead.setDisqualifyReason(req.reason());
        lead.setDisqualifiedAt(Instant.now());
        lead.setUpdatedAt(Instant.now());
        lead.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CRM_LEAD_DISQUALIFY, "leads",
                lead.getId(), lead.getUid())
                .detail(Map.of("reason", req.reason())));
        return LeadDto.from(lead);
    }

    // -------------------------------------------------------------------------

    void markConverted(Lead lead) {
        lead.setLeadStatus(LeadStatus.CONVERTED);
        lead.setConvertedAt(Instant.now());
        lead.setUpdatedAt(Instant.now());
        lead.setUpdatedBy(actorId());
    }

    Lead findByUidInternal(String uid) {
        return require(uid);
    }

    // -------------------------------------------------------------------------

    private Lead require(String uid) {
        return Lookups.orNotFound(leads.findByUid(uid), "Lead", uid);
    }

    private void assertEditable(Lead lead) {
        if (lead.getLeadStatus() == LeadStatus.CONVERTED || lead.getLeadStatus() == LeadStatus.DISQUALIFIED) {
            throw new IllegalStateException("Cannot edit a terminal lead; status: " + lead.getLeadStatus());
        }
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
