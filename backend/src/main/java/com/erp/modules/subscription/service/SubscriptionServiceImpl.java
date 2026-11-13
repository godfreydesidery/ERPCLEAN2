package com.erp.modules.subscription.service;

import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.subscription.domain.dto.PlanCatalogueDto;
import com.erp.modules.subscription.domain.dto.RecordSubscriptionPaymentRequest;
import com.erp.modules.subscription.domain.dto.SubscriptionSummaryDto;
import com.erp.modules.subscription.domain.dto.TenantSubscriptionDto;
import com.erp.modules.subscription.domain.dto.UpdateTenantSubscriptionRequest;
import com.erp.modules.subscription.domain.entity.OrganisationSubscription;
import com.erp.modules.subscription.domain.entity.SubscriptionPayment;
import com.erp.modules.subscription.domain.enums.CapResource;
import com.erp.modules.subscription.domain.enums.PlanCode;
import com.erp.modules.subscription.domain.enums.PlanModule;
import com.erp.modules.subscription.repository.OrganisationSubscriptionRepository;
import com.erp.modules.subscription.repository.SubscriptionPaymentRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.PlatformOrganisationRegistry;
import com.erp.platform.security.RequestContext;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SubscriptionServiceImpl implements SubscriptionService {

    /** D-7: a new tenant's trial length. */
    static final int TRIAL_DAYS = 14;

    private final OrganisationSubscriptionRepository subscriptions;
    private final SubscriptionPaymentRepository payments;
    private final OrganisationRepository organisations;
    private final PlatformOrganisationRegistry platformRegistry;
    private final SubscriptionGate gate;
    private final AuditService audit;

    public SubscriptionServiceImpl(OrganisationSubscriptionRepository subscriptions,
                                   SubscriptionPaymentRepository payments,
                                   OrganisationRepository organisations,
                                   PlatformOrganisationRegistry platformRegistry,
                                   SubscriptionGate gate,
                                   AuditService audit) {
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.organisations = organisations;
        this.platformRegistry = platformRegistry;
        this.gate = gate;
        this.audit = audit;
    }

    @Override
    public PlanCatalogueDto catalogue() {
        return PlanCatalogueDto.current();
    }

    @Override
    public TenantSubscriptionDto get(String organisationUid) {
        Organisation org = requireTenant(organisationUid);
        return toDto(org, subscriptions.findByOrganisationId(org.getId()).orElse(null));
    }

    @Override
    @Transactional
    public TenantSubscriptionDto update(String organisationUid,
                                        UpdateTenantSubscriptionRequest r) {
        Organisation org = requireTenant(organisationUid);
        Long actor = actorId();
        Optional<OrganisationSubscription> existing =
                subscriptions.findByOrganisationId(org.getId());
        OrganisationSubscription sub = existing.orElseGet(
                () -> new OrganisationSubscription(org.getId(), r.planCode(), actor));
        String before = existing.map(SubscriptionServiceImpl::describe).orElse("none");
        sub.changeTerms(r.planCode(), r.addOnRestaurant(), r.addOnOrbixHq(), r.billingCycle(),
                r.trialEndsOn(), r.paidUntil(), r.graceDays(), r.readOnlyDays(),
                r.maxUsersOverride(), r.maxBranchesOverride(), r.maxTillsOverride(),
                r.maxCompaniesOverride(), blankToNull(r.notes()), actor);
        sub = subscriptions.save(sub);
        gate.evict(org.getId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", describe(sub));
        audit.record(AuditEvent.of(AuditActions.ORG_SUBSCRIPTION_CHANGE, "organisations",
                org.getId(), org.getUid()).detail(detail));
        return toDto(org, sub);
    }

    @Override
    @Transactional
    public TenantSubscriptionDto recordPayment(String organisationUid,
                                               RecordSubscriptionPaymentRequest r) {
        Organisation org = requireTenant(organisationUid);
        OrganisationSubscription sub = subscriptions.findByOrganisationId(org.getId())
                .orElseThrow(() -> new ConflictException(
                        "Assign a plan to this tenant before recording a payment."));
        if (r.paidUntil().isBefore(r.paidOn())) {
            throw new IllegalArgumentException("Paid until cannot be before the payment date.");
        }
        Long actor = actorId();
        SubscriptionPayment payment = payments.save(new SubscriptionPayment(org.getId(),
                r.amount(), r.currencyCode().toUpperCase(Locale.ROOT), r.paidOn(), r.paidUntil(),
                blankToNull(r.reference()), blankToNull(r.note()), actor));
        LocalDate previous = sub.getPaidUntil();
        sub.paidUntil(r.paidUntil(), actor);
        subscriptions.save(sub);
        gate.evict(org.getId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("paymentUid", payment.getUid());
        detail.put("amount", payment.getAmount().toPlainString());
        detail.put("currency", payment.getCurrencyCode());
        detail.put("paidOn", payment.getPaidOn().toString());
        detail.put("paidUntilBefore", String.valueOf(previous));
        detail.put("paidUntilAfter", payment.getPaidUntil().toString());
        audit.record(AuditEvent.of(AuditActions.ORG_SUBSCRIPTION_PAYMENT, "organisations",
                org.getId(), org.getUid()).detail(detail));
        return toDto(org, sub);
    }

    @Override
    @Transactional
    public void startTrial(Long organisationId, PlanCode plan, boolean restaurant,
                           boolean orbixHq) {
        if (subscriptions.findByOrganisationId(organisationId).isPresent()) {
            return;
        }
        OrganisationSubscription sub = new OrganisationSubscription(organisationId, plan,
                actorId());
        // "Today" in the new organisation's own time zone, read from the row provisioning just wrote.
        LocalDate today = LocalDate.now(zoneOf(organisationId));
        sub.startTrial(today.plusDays(TRIAL_DAYS), restaurant, orbixHq);
        subscriptions.save(sub);
        gate.evict(organisationId);
    }

    @Override
    public Optional<SubscriptionSummaryDto> summaryFor(Long organisationId) {
        return gate.entitlementOf(organisationId).map(e -> new SubscriptionSummaryDto(
                e.plan().name(), e.plan().displayName(),
                gate.stageOf(organisationId).name(), e.dueDate(), e.readOnlyFrom(),
                e.suspendedFrom(), e.restaurant(), e.orbixHq()));
    }

    // -------------------------------------------------------------------------

    /** Platform operator only; the platform organisation itself has no subscription. */
    private Organisation requireTenant(String organisationUid) {
        RequestContext.Principal caller = RequestContext.get();
        // NOT FOUND rather than FORBIDDEN, as for the other tenant-console reads: no existence oracle.
        if (caller == null || !caller.root()) {
            throw new NotFoundException("Organisation not found.");
        }
        Organisation org = organisations.findByUid(organisationUid)
                .orElseThrow(() -> new NotFoundException("Organisation not found."));
        if (platformRegistry.isPlatform(org.getId())) {
            throw new ConflictException("The platform organisation has no subscription.");
        }
        return org;
    }

    private TenantSubscriptionDto toDto(Organisation org, OrganisationSubscription sub) {
        Map<CapResource, Long> inUse = new HashMap<>();
        for (CapResource c : CapResource.values()) {
            inUse.put(c, countOf(org.getId(), c));
        }
        if (sub == null) {
            List<TenantSubscriptionDto.CapUsage> caps = Arrays.stream(CapResource.values())
                    .map(c -> new TenantSubscriptionDto.CapUsage(c.name(), null, null, null,
                            inUse.get(c)))
                    .toList();
            List<PlanCatalogueDto.ModuleDto> all = Arrays.stream(PlanModule.values())
                    .map(PlanCatalogueDto.ModuleDto::of).toList();
            return new TenantSubscriptionDto(org.getUid(), org.getName(), false, null, null,
                    false, false, null, null, null, 7, 7, null, "UNRESTRICTED", null, null, null,
                    caps, all, List.of());
        }
        Entitlement e = Entitlement.of(sub, zoneOf(org.getId()));
        List<TenantSubscriptionDto.CapUsage> caps = Arrays.stream(CapResource.values())
                .map(c -> new TenantSubscriptionDto.CapUsage(c.name(), e.plan().cap(c),
                        e.override(c), e.cap(c), inUse.get(c)))
                .toList();
        List<PlanCatalogueDto.ModuleDto> modules = Arrays.stream(PlanModule.values())
                .filter(e::includes).map(PlanCatalogueDto.ModuleDto::of).toList();
        List<TenantSubscriptionDto.Payment> history =
                payments.findByOrganisationIdOrderByPaidOnDescIdDesc(org.getId()).stream()
                        .map(p -> new TenantSubscriptionDto.Payment(p.getUid(), p.getAmount(),
                                p.getCurrencyCode(), p.getPaidOn(), p.getPaidUntil(),
                                p.getReference(), p.getNote(), p.getCreatedAt(),
                                p.getCreatedBy() == null ? null
                                        : subscriptions.usernameOf(p.getCreatedBy()).orElse(null)))
                        .toList();
        return new TenantSubscriptionDto(org.getUid(), org.getName(), true, e.plan().name(),
                e.plan().displayName(), e.restaurant(), e.orbixHq(), sub.getBillingCycle().name(),
                sub.getTrialEndsOn(), sub.getPaidUntil(), sub.getGraceDays(),
                sub.getReadOnlyDays(), sub.getNotes(),
                e.stageOn(LocalDate.now(e.zone())).name(), e.dueDate(), e.readOnlyFrom(),
                e.suspendedFrom(), caps, modules, history);
    }

    private long countOf(Long organisationId, CapResource resource) {
        return switch (resource) {
            case USERS -> subscriptions.countActiveUsers(organisationId);
            case BRANCHES -> subscriptions.countBranches(organisationId);
            case TILLS -> subscriptions.countActiveTills(organisationId);
            case COMPANIES -> subscriptions.countCompanies(organisationId);
        };
    }

    private java.time.ZoneId zoneOf(Long organisationId) {
        return organisations.findScopedById(organisationId)
                .map(o -> {
                    try {
                        return java.time.ZoneId.of(o.getDefaultTimeZone());
                    } catch (RuntimeException badZone) {
                        return java.time.ZoneId.of("Africa/Dar_es_Salaam");
                    }
                })
                .orElse(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
    }

    private static String describe(OrganisationSubscription s) {
        return s.getPlanCode().name()
                + (s.isAddOnRestaurant() ? "+RESTAURANT" : "")
                + (s.isAddOnOrbixHq() ? "+ORBIXHQ" : "")
                + " cycle=" + s.getBillingCycle()
                + " trialEndsOn=" + s.getTrialEndsOn()
                + " paidUntil=" + s.getPaidUntil()
                + " grace=" + s.getGraceDays() + " readOnly=" + s.getReadOnlyDays()
                + " caps(u/b/t/c)=" + s.getMaxUsersOverride() + "/" + s.getMaxBranchesOverride()
                + "/" + s.getMaxTillsOverride() + "/" + s.getMaxCompaniesOverride();
    }

    private static Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p == null ? null : p.userId();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
