package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.support.ReportQueryTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

/**
 * The till's category buttons (Tulia 2026-11-02): the category list and the product list filtered
 * by one category, matched trimmed and case-insensitively.
 */
class ProductCategoriesIT extends ReportQueryTestBase {

    @Autowired private ProductService products;

    @BeforeEach
    void seedProducts() {
        long companyId = company.getId();
        long pcs = seed.unit(companyId, "PCS");
        category(seed.product(companyId, pcs, "COKE", "Coke", null), "Drinks");
        category(seed.product(companyId, pcs, "JUICE", "Juice", null), " drinks ");
        category(seed.product(companyId, pcs, "CHIPS", "Chips", null), "Food");
        seed.product(companyId, pcs, "BREAD", "Bread", null);
        long old = seed.product(companyId, pcs, "OLD", "Old Item", null);
        category(old, "Retired");
        jdbc.update("UPDATE products SET status = 'ARCHIVED' WHERE id = ?", old);
    }

    private void category(long productId, String category) {
        jdbc.update("UPDATE products SET category = ? WHERE id = ?", category, productId);
    }

    @Test
    void categories_oncePerName_sorted_activeSellableOnly() {
        assertThat(products.listCategories(company.getId())).containsExactly("Drinks", "Food");
    }

    @Test
    void list_byCategory_matchesHoweverTyped_andStillSearches() {
        assertThat(products.list(company.getId(), null, "DRINKS", Pageable.unpaged()).getContent())
                .extracting(ProductDto::name).containsExactlyInAnyOrder("Coke", "Juice");
        assertThat(products.list(company.getId(), "jui", "drinks", Pageable.unpaged()).getContent())
                .extracting(ProductDto::name).containsExactly("Juice");
        assertThat(products.list(company.getId(), null, " ", Pageable.unpaged()).getContent())
                .as("a blank category lists everything").hasSize(5);
    }
}
