package com.erp.platform.tenant;

/**
 * The organisation a tenant-data operation acts on. Carries only what the engine needs; the
 * caller (iam) has already authorised the operation and loaded the row.
 */
public record TenantRef(long id, String uid, String name) {
}
