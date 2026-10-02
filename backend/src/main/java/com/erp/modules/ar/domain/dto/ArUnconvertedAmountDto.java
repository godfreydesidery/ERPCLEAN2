package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;

/**
 * A foreign-currency AR amount that has NO reliable base-currency value, shown in its own
 * currency and never summed into a base-currency total (owner ruling 2026-10-02).
 *
 * <p>Migration V62 back-filled {@code fx_rate = 1} and {@code base_* = face} onto every AR
 * document that existed before multi-currency, whatever its currency. For a foreign-currency row
 * a stored rate of exactly 1 is therefore the V62 fill, not a real rate, and its "base" figure is
 * really the foreign amount. Those rows are reported here, per currency, instead.
 *
 * @param currency  ISO 4217 code of the foreign amount
 * @param amount    net amount in that currency (open items − on-account receipts − unapplied
 *                  credit notes); may be negative
 * @param itemCount number of documents behind the amount
 */
public record ArUnconvertedAmountDto(String currency, BigDecimal amount, int itemCount) {}
