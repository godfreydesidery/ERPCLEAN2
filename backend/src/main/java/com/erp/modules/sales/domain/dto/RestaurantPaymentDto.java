package com.erp.modules.sales.domain.dto;

/** Result of payment: the PAID order and the POS sale (invoice) it became — print its receipt. */
public record RestaurantPaymentDto(RestaurantOrderDto order, SalesInvoiceDto invoice) {}
