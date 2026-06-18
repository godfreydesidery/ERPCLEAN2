package com.erp.modules.projects.service;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.projects.domain.dto.CreateProjectRequest;
import com.erp.modules.projects.domain.dto.ProjectDto;
import com.erp.modules.projects.domain.dto.UpdateProjectRequest;
import com.erp.modules.projects.domain.entity.Project;
import com.erp.modules.projects.domain.enums.ProjectStatus;
import com.erp.modules.projects.repository.ProjectRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project CRUD + lifecycle (ADR-0033 D-2, FR-PROJ-01/02, NFR-PROJ-01/03).
 */
@Service
@Transactional
public class ProjectServiceImpl implements ProjectService {

    private static final String AUDIT_TARGET = "projects";

    private final ProjectRepository     projects;
    private final CompanyRepository     companies;
    private final BranchRepository      branches;
    private final CustomerRepository    customers;
    private final ProjectNumberGenerator numberGen;
    private final ScopeGuard            scopeGuard;
    private final AuditService          audit;

    public ProjectServiceImpl(ProjectRepository projects,
                              CompanyRepository companies,
                              BranchRepository branches,
                              CustomerRepository customers,
                              ProjectNumberGenerator numberGen,
                              ScopeGuard scopeGuard,
                              AuditService audit) {
        this.projects   = projects;
        this.companies  = companies;
        this.branches   = branches;
        this.customers  = customers;
        this.numberGen  = numberGen;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public ProjectDto create(CreateProjectRequest req, RequestContext.Principal principal) {
        var company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> new NotFoundException("Company not found: " + req.companyUid()));
        scopeGuard.assertCanActIn(principal, company.getId());

        var branch = branches.findByUid(req.branchUid())
                .orElseThrow(() -> new NotFoundException("Branch not found: " + req.branchUid()));

        String number = numberGen.next(company.getId());
        var project = new Project(company.getId(), branch.getId(), number,
                req.name(), company.getBaseCurrency(), principal.userId());

        project.setPlannedStartDate(req.plannedStartDate());
        project.setPlannedEndDate(req.plannedEndDate());
        project.setBudgetAmount(req.budgetAmount());
        project.setNotes(req.notes());

        if (req.customerUid() != null) {
            var customer = customers.findByUid(req.customerUid())
                    .orElseThrow(() -> new NotFoundException("Customer not found: " + req.customerUid()));
            project.setCustomerId(customer.getId());
        }

        project = projects.save(project);

        audit.record(AuditEvent.of(AuditActions.PROJECT_CREATE, AUDIT_TARGET,
                project.getId(), project.getUid())
                .detail(Map.of("projectNumber", number, "name", req.name())));

        return toDto(project);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectDto getByUid(String uid, RequestContext.Principal principal) {
        Project project = findActive(uid);
        scopeGuard.assertCanActIn(principal, project.getCompanyId());
        return toDto(project);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProjectDto> list(Long companyId, MasterStatus status, ProjectStatus projectStatus,
                                 Pageable pageable, RequestContext.Principal principal) {
        scopeGuard.assertCanActIn(principal, companyId);
        if (projectStatus != null) {
            return projects.findByCompanyIdAndStatusAndProjectStatus(
                    companyId, status != null ? status : MasterStatus.ACTIVE,
                    projectStatus, pageable).map(this::toDto);
        }
        return projects.findByCompanyIdAndStatus(
                companyId, status != null ? status : MasterStatus.ACTIVE,
                pageable).map(this::toDto);
    }

    @Override
    public ProjectDto update(String uid, UpdateProjectRequest req, RequestContext.Principal principal) {
        Project project = findActive(uid);
        scopeGuard.assertCanActIn(principal, project.getCompanyId());

        project.setName(req.name());
        project.setPlannedStartDate(req.plannedStartDate());
        project.setPlannedEndDate(req.plannedEndDate());
        project.setBudgetAmount(req.budgetAmount());
        project.setNotes(req.notes());
        project.setUpdatedAt(Instant.now());
        project.setUpdatedBy(principal.userId());

        if (req.customerUid() != null) {
            var customer = customers.findByUid(req.customerUid())
                    .orElseThrow(() -> new NotFoundException("Customer not found: " + req.customerUid()));
            project.setCustomerId(customer.getId());
        } else {
            project.setCustomerId(null);
        }

        audit.record(AuditEvent.of(AuditActions.PROJECT_UPDATE, AUDIT_TARGET,
                project.getId(), project.getUid())
                .detail(Map.of("name", req.name())));

        return toDto(project);
    }

    @Override
    public ProjectDto changeStatus(String uid, ProjectStatus targetStatus,
                                   RequestContext.Principal principal) {
        Project project = findActive(uid);
        scopeGuard.assertCanActIn(principal, project.getCompanyId());

        ProjectStatus current = project.getProjectStatus();
        validateTransition(current, targetStatus);

        project.setProjectStatus(targetStatus);
        project.setUpdatedAt(Instant.now());
        project.setUpdatedBy(principal.userId());

        switch (targetStatus) {
            case ACTIVE     -> project.setActivatedAt(Instant.now());
            case COMPLETED  -> project.setCompletedAt(Instant.now());
            case CANCELLED  -> project.setCancelledAt(Instant.now());
            default -> { /* ON_HOLD / DRAFT — no stamp */ }
        }

        audit.record(AuditEvent.of(AuditActions.PROJECT_STATUS_CHANGE, AUDIT_TARGET,
                project.getId(), project.getUid())
                .detail(Map.of("from", current.name(), "to", targetStatus.name())));

        return toDto(project);
    }

    @Override
    public ProjectDto archive(String uid, RequestContext.Principal principal) {
        Project project = findActive(uid);
        scopeGuard.assertCanActIn(principal, project.getCompanyId());

        project.setStatus(MasterStatus.ARCHIVED);
        project.setUpdatedAt(Instant.now());
        project.setUpdatedBy(principal.userId());

        audit.record(AuditEvent.of(AuditActions.PROJECT_ARCHIVE, AUDIT_TARGET,
                project.getId(), project.getUid()));

        return toDto(project);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Project findActive(String uid) {
        return projects.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Project not found: " + uid));
    }

    private void validateTransition(ProjectStatus from, ProjectStatus to) {
        boolean valid = switch (to) {
            case DRAFT      -> false; // cannot go back to DRAFT
            case ACTIVE     -> from == ProjectStatus.DRAFT || from == ProjectStatus.ON_HOLD;
            case ON_HOLD    -> from == ProjectStatus.ACTIVE;
            case COMPLETED  -> from == ProjectStatus.ACTIVE || from == ProjectStatus.ON_HOLD;
            case CANCELLED  -> from != ProjectStatus.COMPLETED && from != ProjectStatus.CANCELLED;
        };
        if (!valid) {
            throw new IllegalStateException(
                    "Invalid project status transition: " + from + " → " + to);
        }
    }

    private ProjectDto toDto(Project p) {
        return new ProjectDto(
                p.getId(), p.getUid(),
                p.getCompanyId(), p.getBranchId(),
                p.getProjectNumber(), p.getName(),
                p.getCustomerId(), p.getManagerUserId(),
                p.getProjectStatus(),
                p.getPlannedStartDate(), p.getPlannedEndDate(),
                p.getActualStartDate(), p.getActualEndDate(),
                p.getBudgetAmount(), CurrencyCode.value(p.getCurrency()),
                p.getNotes(), p.getStatus(),
                p.getActivatedAt(), p.getCompletedAt(), p.getCancelledAt()
        );
    }
}
