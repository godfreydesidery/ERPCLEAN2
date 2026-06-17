package com.erp.platform.common.money;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA {@link AttributeConverter} that maps {@link CurrencyCode} ↔ {@code String} (ADR-0039 D-1).
 *
 * <p>{@code autoApply = true} activates the converter for every {@code CurrencyCode}-typed field
 * in the persistence unit, with no per-field {@code @Convert} annotation required.
 * Because {@code autoApply} only affects fields of type {@code CurrencyCode}, it is safe to
 * enable globally — it has no effect on the existing {@code String} currency fields.
 *
 * <p>Null handling: {@code null} entity value → SQL {@code NULL} and vice versa, preserving the
 * both-null-or-both-set invariant of {@link Money}.
 *
 * <p>No schema change: the underlying column stays {@code CHAR(3)} / {@code VARCHAR(3)}.
 */
@Converter(autoApply = true)
public class CurrencyCodeConverter implements AttributeConverter<CurrencyCode, String> {

    @Override
    public String convertToDatabaseColumn(CurrencyCode attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public CurrencyCode convertToEntityAttribute(String dbData) {
        return (dbData == null || dbData.isBlank()) ? null : CurrencyCode.of(dbData);
    }
}
