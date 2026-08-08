package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.DiscountPolicyDto;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.platform.common.api.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * Reads a company's discount-approval policy (K7) from {@code sales_settings} — the two columns
 * added by V95, sitting next to {@code below_cost_action} and {@code allow_negative_stock}.
 *
 * <p><b>The default is OFF, and OFF is what every existing tenant reads.</b> V95 adds
 * {@code discount_approval_action} with a {@code DEFAULT 'OFF'}, so every already-populated row —
 * and every row the seeder creates — carries OFF until somebody deliberately changes it on the Sales
 * Settings screen. {@link DiscountAuthorisationGuard} returns immediately on OFF, so no existing
 * sale, till key-press or API call behaves any differently after this wiring than before it.
 *
 * <p><b>A company with no settings row at all reads OFF too</b>, and that is not an arbitrary
 * choice: it is the SAME answer {@code SalesSettingsServiceImpl.getByCompanyUid} gives for that
 * state (it hands back a transient default row whose field initialiser is OFF). The two must not
 * drift. The negative-stock toggle shipped broken to a live client for exactly this reason — the
 * screen and the guard disagreed about what a missing row meant, each layer's own unit tests were
 * green, and the till oversold while the screen said "protected".
 * {@code DiscountPolicySettingCrossLayerContractTest} pins the agreement.
 */
@Component
public class DiscountPolicyProvider {

    private final SalesSettingsRepository settings;

    public DiscountPolicyProvider(SalesSettingsRepository settings) {
        this.settings = settings;
    }

    /**
     * The company's policy, or OFF when the company has no settings row.
     *
     * @param companyId the tenant whose policy applies — always taken from a LOADED entity by the
     *                  caller (the invoice's own {@code companyId}), never from a request parameter
     */
    public DiscountPolicyDto policyFor(Long companyId) {
        if (companyId == null) {
            // Defensive: an unscoped call cannot be answered, and guessing a policy for "no company"
            // would either weaken the control or reject sales at random. Neither is acceptable, so
            // this fails loudly rather than silently — it is unreachable from every current caller,
            // all of which pass an invoice's own companyId.
            throw new NotFoundException("Sales settings are not available for this company.");
        }
        return settings.findByCompanyId(companyId)
                .map(s -> new DiscountPolicyDto(s.getDiscountApprovalAction(),
                                                s.getMaxDiscountPercent()))
                .orElseGet(DiscountPolicyDto::off);
    }
}
