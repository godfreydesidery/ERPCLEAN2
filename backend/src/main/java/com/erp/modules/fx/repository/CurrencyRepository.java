package com.erp.modules.fx.repository;

import com.erp.modules.fx.domain.entity.Currency;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the global {@code currencies} master (no company_id — see ADR-0036 D-2).
 */
public interface CurrencyRepository extends JpaRepository<Currency, Long> {

    Optional<Currency> findByUid(String uid);

    Optional<Currency> findByCode(String code);

    /** All active currencies, ordered by code for consistent display. */
    List<Currency> findByActiveTrueOrderByCodeAsc();
}
