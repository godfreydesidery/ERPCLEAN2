package com.erp.modules.sales.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Routes a product category to a prep station within a branch (ADR-0065 D-4): "Drinks → Bar".
 *
 * <p>The category is the free-text {@code products.category}, stored normalised (trimmed, inner
 * whitespace collapsed) and matched case-insensitively — {@code ux_prep_station_category_branch_category}
 * is on {@code lower(category)}. Configuration, not a document: the routing screen replaces a
 * branch's whole set, so rows are deleted freely.
 */
@Getter
@Entity
@Table(name = "prep_station_categories")
public class PrepStationCategory extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "prep_station_id", nullable = false, updatable = false)
    private Long prepStationId;

    @Column(name = "category", nullable = false, length = 60, updatable = false)
    private String category;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected PrepStationCategory() {}

    public PrepStationCategory(Long companyId, Long branchId, Long prepStationId, String category,
                               Long createdBy) {
        this.companyId     = companyId;
        this.branchId      = branchId;
        this.prepStationId = prepStationId;
        this.category      = normalise(category);
        this.createdBy     = createdBy;
    }

    /**
     * The one normal form for a category label: trimmed, runs of whitespace collapsed to one space.
     * Case is kept for display; matching ignores it. Returns null for a blank label.
     */
    public static String normalise(String category) {
        if (category == null) {
            return null;
        }
        String s = category.trim().replaceAll("\\s+", " ");
        return s.isEmpty() ? null : s;
    }

    /** The case-insensitive matching key for a category label; null for a blank one. */
    public static String key(String category) {
        String s = normalise(category);
        return s == null ? null : s.toLowerCase(java.util.Locale.ROOT);
    }
}
