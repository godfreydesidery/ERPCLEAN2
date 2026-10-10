package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.GlConfigRepository;
import com.erp.platform.common.api.AccountingSetupException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ACC-21: an unmapped posting role is an {@link AccountingSetupException}, so the API can show a
 * storekeeper a plain sentence while the accountant still learns which role to map.
 */
class GLConfigResolverTest {

    private final GlConfigRepository configs = mock(GlConfigRepository.class);
    private final ChartOfAccountRepository accounts = mock(ChartOfAccountRepository.class);
    private final GLConfigResolver resolver = new GLConfigResolver(configs, accounts);

    @Test
    void unmappedRole_throwsAccountingSetupException_withAReadableRoleName() {
        when(configs.findByCompanyIdAndConfigKey(1L, GlConfigKey.STOCK_ADJUSTMENT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(1L, GlConfigKey.STOCK_ADJUSTMENT))
                .isInstanceOf(AccountingSetupException.class)
                .hasMessageContaining("Stock Adjustment (STOCK_ADJUSTMENT)")
                .satisfies(ex -> assertThat(((AccountingSetupException) ex).userMessage())
                        .isEqualTo(AccountingSetupException.DEFAULT_USER_MESSAGE));
    }

    @Test
    void describe_titleCasesTheKey() {
        assertThat(GLConfigResolver.describe(GlConfigKey.RETAINED_EARNINGS))
                .isEqualTo("Retained Earnings (RETAINED_EARNINGS)");
    }
}
