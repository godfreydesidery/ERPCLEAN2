package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How long a till session may stay open and still take sales, for one company (V113).
 *
 * <p>The company's own {@code sales_settings.pos_session_max_open_hours} wins when it is set —
 * {@code 0} meaning no limit, as zero already does in the server setting; otherwise the server
 * setting {@code erp.pos.session.max-open-age} (default 36 h) applies.
 *
 * <p>This is the single place both the sale guard ({@code PosSaleServiceImpl}) and the settings
 * screen ({@code SalesSettingsServiceImpl}, as the "effective" value) read, so the screen can never
 * describe a limit the till does not enforce.
 */
@Component
public class PosSessionAgePolicy {

    private final SalesSettingsRepository settings;
    private final Duration serverDefault;

    public PosSessionAgePolicy(SalesSettingsRepository settings,
                               @Value("${erp.pos.session.max-open-age:PT36H}") Duration serverDefault) {
        this.settings      = settings;
        this.serverDefault = serverDefault;
    }

    /** The limit enforced for {@code companyId}. Zero means the check is off (no limit). */
    public Duration maxOpenAge(Long companyId) {
        if (companyId == null) {
            return serverDefault;
        }
        return settings.findByCompanyId(companyId)
                .map(SalesSettings::getPosSessionMaxOpenHours)
                .map(h -> Duration.ofHours(h))
                .orElse(serverDefault);
    }

    /**
     * The same answer in whole hours, for the settings screen: the company value when set, else the
     * server setting. {@code 0} = no limit.
     */
    public int effectiveHours(Integer companyHours) {
        if (companyHours != null) {
            return companyHours;
        }
        return serverDefaultHours();
    }

    /** The server setting in whole hours; {@code 0} when it is switched off. */
    public int serverDefaultHours() {
        if (serverDefault == null || serverDefault.isZero() || serverDefault.isNegative()) {
            return 0;
        }
        return (int) serverDefault.toHours();
    }
}
