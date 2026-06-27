package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.CreateStockLocationRequest;
import com.erp.modules.stock.domain.dto.StockLocationDto;
import com.erp.modules.stock.domain.dto.UpdateStockLocationRequest;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Location master operations (ADR-0028 D-4, FR-INVD-01..04).
 */
public interface StockLocationService {

    /** Create a new location within the caller's active branch. */
    StockLocationDto create(CreateStockLocationRequest request);

    /** Edit a location's name / type. */
    StockLocationDto update(String locationUid, UpdateStockLocationRequest request);

    /** Deactivate a location (soft-delete). Rejects if it is the only/default location. */
    void deactivate(String locationUid);

    /** Reactivate a previously deactivated location. */
    void reactivate(String locationUid);

    /** Set this location as the branch default (clears any existing default). */
    StockLocationDto setDefault(String locationUid);

    /** Get a single location by uid. */
    StockLocationDto getByUid(String locationUid);

    /** Paged list of locations for the caller's active branch. */
    Page<StockLocationDto> listForBranch(Pageable pageable);

    /**
     * Paged list of locations for an explicitly-chosen branch. When {@code branchUid} is null/blank
     * this is equivalent to {@link #listForBranch(Pageable)} (the caller's active branch). Otherwise
     * the branch is resolved by uid and validated to belong to the caller's company and (for a
     * non-root caller) to be one of their active {@code user_branch} assignments — mirroring the
     * X-Branch-Uid override check (ADR-0003).
     */
    Page<StockLocationDto> listForBranch(String branchUid, Pageable pageable);

    /** All active locations for a branch (dropdown use). */
    List<StockLocationDto> activeForBranch(String branchUid);
}
