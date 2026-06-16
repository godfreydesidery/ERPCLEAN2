import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Branch } from '../models/branch.model';
import { Company } from '../models/company.model';
import { AgentModel, CustomerModel } from '../models/party.model';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AgentService } from '../parties/agent.service';
import { CustomerService } from '../parties/customer.service';
import {
  AssignRouteAgentRequest,
  AssignRouteBranchRequest,
  AssignRouteCustomerRequest,
  RouteAgentDto,
  RouteBranchDto,
  RouteCustomerDto,
  RouteDto,
  UpdateRouteRequest,
} from './models/route.model';
import { RoutesService } from './routes.service';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Route detail + edit screen. Route: /admin/routes/uid/:uid.
 * Header: code, name, status; edit form (name, locationIdentifier); archive/restore.
 * Customers panel: table + customer-search picker (scoped to route's company). ROUTE.ASSIGN.
 * Agents panel: table (with Primary badge) + agent-search picker (EXTERNAL only). ROUTE.ASSIGN.
 * Branches panel: table + branch picker (route's company branches). ROUTE.ASSIGN.
 * All assignment data comes from server DTOs.
 */
@Component({
  selector: 'app-route-detail',
  imports: [FormsModule, RouterLink],
  templateUrl: './route-detail.component.html',
  styleUrl: './route-detail.component.scss',
})
export class RouteDetailComponent {
  private readonly routesService = inject(RoutesService);
  private readonly customerService = inject(CustomerService);
  private readonly agentService = inject(AgentService);
  private readonly branchService = inject(BranchService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Route header ──────────────────────────────────────────────────────────
  readonly route = signal<RouteDto | null>(null);
  readonly routeState = signal<LoadState>('loading');

  // ── Edit form ─────────────────────────────────────────────────────────────
  readonly fName = signal('');
  readonly fLocationIdentifier = signal('');
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly archiving = signal(false);

  readonly canManage = computed(() => this.session.hasPermission('ROUTE.MANAGE'));
  readonly canAssign = computed(() => this.session.hasPermission('ROUTE.ASSIGN'));

  // ── Customers panel ───────────────────────────────────────────────────────
  readonly customers = signal<RouteCustomerDto[]>([]);
  readonly customersState = signal<LoadState>('loading');
  readonly rowBusyCustomerUid = signal<string | null>(null);

  readonly customerSearchQ = signal('');
  readonly customerResults = signal<CustomerModel[]>([]);
  readonly selectedCustomer = signal<{ uid: string; label: string } | null>(null);
  readonly assigningCustomer = signal(false);
  readonly customerAssignError = signal<string | null>(null);

  private readonly customerSearch$ = new Subject<string>();

  // ── Agents panel ──────────────────────────────────────────────────────────
  readonly agents = signal<RouteAgentDto[]>([]);
  readonly agentsState = signal<LoadState>('loading');
  readonly rowBusyAgentUid = signal<string | null>(null);

  readonly agentSearchQ = signal('');
  readonly agentResults = signal<AgentModel[]>([]);
  readonly selectedAgent = signal<{ uid: string; label: string } | null>(null);
  readonly newAgentIsPrimary = signal(false);
  readonly assigningAgent = signal(false);
  readonly agentAssignError = signal<string | null>(null);

  private readonly agentSearch$ = new Subject<string>();

  // ── Branches panel ────────────────────────────────────────────────────────
  readonly routeBranches = signal<RouteBranchDto[]>([]);
  readonly routeBranchesState = signal<LoadState>('loading');
  readonly rowBusyBranchUid = signal<string | null>(null);

  readonly companies = signal<Company[]>([]);
  readonly companiesState = signal<LoadState>('loading');
  readonly selectedCompanyUid = signal('');
  readonly companyBranches = signal<Branch[]>([]);
  readonly companyBranchesState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly selectedBranchUid = signal('');
  readonly assigningBranch = signal(false);
  readonly branchAssignError = signal<string | null>(null);

  readonly hasCustomers = computed(() => this.customers().length > 0);
  readonly hasAgents = computed(() => this.agents().length > 0);
  readonly hasRouteBranches = computed(() => this.routeBranches().length > 0);

  constructor() {
    // Debounced customer search scoped to the route's company.
    this.customerSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.route()?.companyId;
          if (!companyId || !q.trim()) {
            this.customerResults.set([]);
            return [];
          }
          return this.customerService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.customerResults.set(rows.filter((c) => c.status === 'ACTIVE')),
        error: () => this.customerResults.set([]),
      });

    // Debounced agent search — EXTERNAL only.
    this.agentSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.route()?.companyId;
          if (!companyId || !q.trim()) {
            this.agentResults.set([]);
            return [];
          }
          return this.agentService.list(companyId, q.trim(), 0, 20);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) =>
          // Filter to EXTERNAL only — backend also rejects INTERNAL, but
          // filtering the picker prevents the round-trip rejection.
          this.agentResults.set(rows.filter((a) => a.status === 'ACTIVE' && a.agentKind === 'EXTERNAL')),
        error: () => this.agentResults.set([]),
      });

    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadRoute();
    this.loadCustomers();
    this.loadAgents();
    this.loadRouteBranches();
    this.loadCompanies();
  }

  // ── Route ─────────────────────────────────────────────────────────────────

  private loadRoute(): void {
    this.routeState.set('loading');
    this.routesService.getByUid(this.uid()).subscribe({
      next: (r) => {
        this.route.set(r);
        this.routeState.set('idle');
        this.patchForm(r);
      },
      error: () => this.routeState.set('error'),
    });
  }

  private patchForm(r: RouteDto): void {
    this.fName.set(r.name ?? '');
    this.fLocationIdentifier.set(r.locationIdentifier ?? '');
  }

  save(): void {
    const name = this.fName().trim();
    if (!name) {
      this.saveError.set('Name is required.');
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    const request: UpdateRouteRequest = {
      name,
      locationIdentifier: this.fLocationIdentifier().trim() || undefined,
    };
    this.routesService.update(this.uid(), request).subscribe({
      next: (updated) => {
        this.route.set(updated);
        this.saving.set(false);
        this.alerts.success('Route saved', updated.name);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the route.'));
        this.saving.set(false);
      },
    });
  }

  archive(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.routesService.archive(this.uid()).subscribe({
      next: (updated) => {
        this.archiving.set(false);
        this.route.set(updated);
        this.alerts.success('Route archived');
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not archive the route.'));
      },
    });
  }

  restore(): void {
    if (this.archiving()) return;
    this.archiving.set(true);
    this.routesService.restore(this.uid()).subscribe({
      next: (updated) => {
        this.archiving.set(false);
        this.route.set(updated);
        this.alerts.success('Route restored');
      },
      error: (err) => {
        this.archiving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not restore the route.'));
      },
    });
  }

  // ── Customers ─────────────────────────────────────────────────────────────

  loadCustomers(): void {
    this.customersState.set('loading');
    this.routesService.listCustomers(this.uid()).subscribe({
      next: (rows) => {
        this.customers.set(rows);
        this.customersState.set('idle');
      },
      error: () => this.customersState.set('error'),
    });
  }

  onCustomerSearchChange(q: string): void {
    this.customerSearchQ.set(q);
    this.selectedCustomer.set(null);
    this.customerSearch$.next(q);
  }

  selectCustomer(customer: CustomerModel): void {
    this.selectedCustomer.set({ uid: customer.uid, label: `${customer.code} — ${customer.displayName}` });
    this.customerResults.set([]);
    this.customerSearchQ.set(`${customer.code} — ${customer.displayName}`);
  }

  assignCustomer(): void {
    const selected = this.selectedCustomer();
    if (!selected) {
      this.customerAssignError.set('Select a customer to assign.');
      return;
    }
    this.assigningCustomer.set(true);
    this.customerAssignError.set(null);
    const request: AssignRouteCustomerRequest = { customerUid: selected.uid };
    this.routesService.assignCustomer(this.uid(), request).subscribe({
      next: () => {
        this.selectedCustomer.set(null);
        this.customerSearchQ.set('');
        this.customerResults.set([]);
        this.assigningCustomer.set(false);
        this.alerts.success('Customer assigned');
        this.loadCustomers();
      },
      error: (err) => {
        this.customerAssignError.set(this.messageFrom(err, 'Could not assign customer.'));
        this.assigningCustomer.set(false);
      },
    });
  }

  removeCustomer(rc: RouteCustomerDto): void {
    if (this.rowBusyCustomerUid() !== null) return;
    this.rowBusyCustomerUid.set(rc.customerUid);
    this.routesService.removeCustomer(this.uid(), rc.customerUid).subscribe({
      next: () => {
        this.rowBusyCustomerUid.set(null);
        this.alerts.success('Customer removed', rc.customerName);
        this.loadCustomers();
      },
      error: () => this.rowBusyCustomerUid.set(null),
    });
  }

  // ── Agents ────────────────────────────────────────────────────────────────

  loadAgents(): void {
    this.agentsState.set('loading');
    this.routesService.listAgents(this.uid()).subscribe({
      next: (rows) => {
        this.agents.set(rows);
        this.agentsState.set('idle');
      },
      error: () => this.agentsState.set('error'),
    });
  }

  onAgentSearchChange(q: string): void {
    this.agentSearchQ.set(q);
    this.selectedAgent.set(null);
    this.agentSearch$.next(q);
  }

  selectAgent(agent: AgentModel): void {
    this.selectedAgent.set({ uid: agent.uid, label: `${agent.code} — ${agent.displayName}` });
    this.agentResults.set([]);
    this.agentSearchQ.set(`${agent.code} — ${agent.displayName}`);
  }

  assignAgent(): void {
    const selected = this.selectedAgent();
    if (!selected) {
      this.agentAssignError.set('Select an agent to assign.');
      return;
    }
    this.assigningAgent.set(true);
    this.agentAssignError.set(null);
    const request: AssignRouteAgentRequest = { agentUid: selected.uid, isPrimary: this.newAgentIsPrimary() };
    this.routesService.assignAgent(this.uid(), request).subscribe({
      next: () => {
        this.selectedAgent.set(null);
        this.agentSearchQ.set('');
        this.agentResults.set([]);
        this.newAgentIsPrimary.set(false);
        this.assigningAgent.set(false);
        this.alerts.success('Agent assigned');
        this.loadAgents();
      },
      error: (err) => {
        // Surface EXTERNAL-only rejection inline — backend returns this as a business error.
        this.agentAssignError.set(this.messageFrom(err, 'Could not assign agent. Only EXTERNAL agents may be assigned to a route.'));
        this.assigningAgent.set(false);
      },
    });
  }

  removeAgent(ra: RouteAgentDto): void {
    if (this.rowBusyAgentUid() !== null) return;
    this.rowBusyAgentUid.set(ra.agentUid);
    this.routesService.removeAgent(this.uid(), ra.agentUid).subscribe({
      next: () => {
        this.rowBusyAgentUid.set(null);
        this.alerts.success('Agent removed', ra.agentName);
        this.loadAgents();
      },
      error: () => this.rowBusyAgentUid.set(null),
    });
  }

  // ── Branches ──────────────────────────────────────────────────────────────

  loadRouteBranches(): void {
    this.routeBranchesState.set('loading');
    this.routesService.listBranches(this.uid()).subscribe({
      next: (rows) => {
        this.routeBranches.set(rows);
        this.routeBranchesState.set('idle');
      },
      error: () => this.routeBranchesState.set('error'),
    });
  }

  private loadCompanies(): void {
    this.companiesState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companiesState.set('idle');
          },
          error: () => this.companiesState.set('error'),
        });
      },
      error: () => this.companiesState.set('error'),
    });
  }

  onBranchCompanyChange(companyUid: string): void {
    this.selectedCompanyUid.set(companyUid);
    this.selectedBranchUid.set('');
    this.companyBranches.set([]);
    if (!companyUid) {
      this.companyBranchesState.set('idle');
      return;
    }
    this.companyBranchesState.set('loading');
    this.branchService.list(companyUid).subscribe({
      next: (rows) => {
        this.companyBranches.set(rows);
        this.companyBranchesState.set('idle');
      },
      error: () => this.companyBranchesState.set('error'),
    });
  }

  assignBranch(): void {
    const branchUid = this.selectedBranchUid();
    if (!branchUid) {
      this.branchAssignError.set('Select a branch to assign.');
      return;
    }
    this.assigningBranch.set(true);
    this.branchAssignError.set(null);
    const request: AssignRouteBranchRequest = { branchUid };
    this.routesService.assignBranch(this.uid(), request).subscribe({
      next: () => {
        this.selectedBranchUid.set('');
        this.assigningBranch.set(false);
        this.alerts.success('Branch assigned');
        this.loadRouteBranches();
      },
      error: (err) => {
        this.branchAssignError.set(this.messageFrom(err, 'Could not assign branch.'));
        this.assigningBranch.set(false);
      },
    });
  }

  removeBranch(rb: RouteBranchDto): void {
    if (this.rowBusyBranchUid() !== null) return;
    this.rowBusyBranchUid.set(rb.branchUid);
    this.routesService.removeBranch(this.uid(), rb.branchUid).subscribe({
      next: () => {
        this.rowBusyBranchUid.set(null);
        this.alerts.success('Branch removed', rb.branchName);
        this.loadRouteBranches();
      },
      error: () => this.rowBusyBranchUid.set(null),
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
