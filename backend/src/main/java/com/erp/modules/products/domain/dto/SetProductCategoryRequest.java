package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.Size;

/** Sets a product's category; null or blank clears it. */
public record SetProductCategoryRequest(@Size(max = 60) String category) {}
