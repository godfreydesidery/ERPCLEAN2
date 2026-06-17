package com.erp.modules.parties.domain.dto;

import com.erp.platform.common.money.Money;
import java.math.BigDecimal;

/**
 * Wire representation of a {@link Money} pair per ADR-0005 D-7.
 *
 * @deprecated Use {@link com.erp.platform.common.money.MoneyDto} — the canonical location
 *     since ADR-0007 D-12 promoted it to platform.common.money. This alias is kept so
 *     existing Parties code and tests compile without a bulk rename.
 */
@Deprecated(forRemoval = false)
public record MoneyDto(String amount, String currency) {

    public static MoneyDto from(Money money) {
        if (money == null || !money.isPresent()) {
            return null;
        }
        return new MoneyDto(money.getAmount().toPlainString(), money.getCurrency().value());
    }

    public static Money toMoney(MoneyDto dto) {
        if (dto == null || dto.amount() == null || dto.currency() == null) {
            return null;
        }
        return new Money(new BigDecimal(dto.amount()), dto.currency());
    }
}
