package com.erp.modules.products.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.dto.BarcodeSymbologyRuleDto;
import com.erp.modules.products.domain.dto.CreateBarcodeSymbologyRuleRequest;
import com.erp.modules.products.domain.dto.EmbeddedBarcodeDecode;
import com.erp.modules.products.domain.dto.UpdateBarcodeSymbologyRuleRequest;
import com.erp.modules.products.domain.entity.BarcodeSymbologyRule;
import com.erp.modules.products.repository.BarcodeSymbologyRuleRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Barcode symbology rule administration + embedded-barcode decode hot path (ADR-0044 D-1a / BR-9).
 *
 * <p>Decode algorithm: find the first ACTIVE rule (lowest priority value) whose
 * {@code prefix} is a leading substring of the scanned barcode AND whose offsets fit within
 * the barcode's length. Extracts two substrings by 0-based offset/length, parses the value
 * integer, then scales by {@code 10^(-valueDecimals)}.
 */
@Service
@Transactional
public class BarcodeSymbologyRuleServiceImpl implements BarcodeSymbologyRuleService {

    private final BarcodeSymbologyRuleRepository rules;
    private final CompanyRepository companies;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public BarcodeSymbologyRuleServiceImpl(BarcodeSymbologyRuleRepository rules,
                                           CompanyRepository companies,
                                           ScopeGuard scopeGuard,
                                           AuditService audit) {
        this.rules = rules;
        this.companies = companies;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    @Override
    public BarcodeSymbologyRuleDto create(CreateBarcodeSymbologyRuleRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        if (rules.findByCompanyIdAndCode(companyId, req.code()).isPresent()) {
            throw new ConflictException(
                    "Symbology rule code already exists for this company: " + req.code());
        }

        BarcodeSymbologyRule rule = new BarcodeSymbologyRule(
                companyId,
                req.code(),
                req.name(),
                req.prefix(),
                req.valueKind(),
                req.itemCodeStart(),
                req.itemCodeLength(),
                req.valueStart(),
                req.valueLength(),
                req.valueDecimals(),
                req.itemMatch(),
                req.priority(),
                actorId());

        if (req.checkDigitMode() != null) {
            rule.setCheckDigitMode(req.checkDigitMode());
        }

        BarcodeSymbologyRule saved = rules.save(rule);
        audit.record(AuditEvent.of(AuditActions.SYMBOLOGY_RULE_CREATE,
                        "barcode_symbology_rules", saved.getId(), saved.getUid())
                .detail(Map.of("code", saved.getCode(), "prefix", saved.getPrefix(),
                        "valueKind", saved.getValueKind().name())));
        return BarcodeSymbologyRuleDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public BarcodeSymbologyRuleDto getByUid(String uid) {
        BarcodeSymbologyRule rule = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), rule.getCompanyId());
        return BarcodeSymbologyRuleDto.from(rule);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BarcodeSymbologyRuleDto> listByCompany(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return rules.findByCompanyIdAndStatusOrderByPriorityAsc(companyId, MasterStatus.ACTIVE)
                .stream()
                .map(BarcodeSymbologyRuleDto::from)
                .toList();
    }

    @Override
    public BarcodeSymbologyRuleDto updateByUid(String uid, UpdateBarcodeSymbologyRuleRequest req) {
        BarcodeSymbologyRule rule = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), rule.getCompanyId());

        rule.setName(req.name());
        rule.setPrefix(req.prefix());
        rule.setValueKind(req.valueKind());
        rule.setItemCodeStart(req.itemCodeStart());
        rule.setItemCodeLength(req.itemCodeLength());
        rule.setValueStart(req.valueStart());
        rule.setValueLength(req.valueLength());
        rule.setValueDecimals(req.valueDecimals());
        rule.setItemMatch(req.itemMatch());
        rule.setCheckDigitMode(req.checkDigitMode());
        rule.setPriority(req.priority());
        rule.setUpdatedAt(Instant.now());
        rule.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.SYMBOLOGY_RULE_UPDATE,
                "barcode_symbology_rules", rule.getId(), rule.getUid()));
        return BarcodeSymbologyRuleDto.from(rule);
    }

    @Override
    public void archiveByUid(String uid) {
        BarcodeSymbologyRule rule = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), rule.getCompanyId());
        rule.setStatus(MasterStatus.ARCHIVED);
        rule.setUpdatedAt(Instant.now());
        rule.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.SYMBOLOGY_RULE_ARCHIVE,
                "barcode_symbology_rules", rule.getId(), rule.getUid()));
    }

    // -------------------------------------------------------------------------
    // Decode hot path
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<EmbeddedBarcodeDecode> decode(Long companyId, String barcode) {
        if (barcode == null || barcode.isEmpty()) {
            return Optional.empty();
        }
        List<BarcodeSymbologyRule> activeRules =
                rules.findByCompanyIdAndStatusOrderByPriorityAsc(companyId, MasterStatus.ACTIVE);

        for (BarcodeSymbologyRule rule : activeRules) {
            if (!barcode.startsWith(rule.getPrefix())) {
                continue;
            }

            int itemEnd = rule.getItemCodeStart() + rule.getItemCodeLength();
            int valueEnd = rule.getValueStart() + rule.getValueLength();
            if (itemEnd > barcode.length() || valueEnd > barcode.length()) {
                // barcode too short for this rule — try next
                continue;
            }

            String itemCode = barcode.substring(rule.getItemCodeStart(), itemEnd);
            String rawValueStr = barcode.substring(rule.getValueStart(), valueEnd);

            long rawInt;
            try {
                rawInt = Long.parseLong(rawValueStr);
            } catch (NumberFormatException e) {
                // non-numeric embedded field — rule doesn't apply
                continue;
            }

            BigDecimal decoded = BigDecimal.valueOf(rawInt)
                    .scaleByPowerOfTen(-rule.getValueDecimals());

            return Optional.of(new EmbeddedBarcodeDecode(itemCode, rule.getValueKind(), decoded));
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private BarcodeSymbologyRule require(String uid) {
        return Lookups.orNotFound(rules.findByUid(uid), "BarcodeSymbologyRule", uid);
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
