package com.erp.platform.bootstrap;

import java.util.Locale;

/**
 * Derives an organisation's alias — the {@code @alias} half of a composed username (ADR-0062 D-7).
 *
 * <h2>Why this is shared rather than duplicated</h2>
 *
 * Two places create tenants: {@code BootstrapRunner} (the first one, on an empty database) and
 * {@code TenantProvisioningService.provision} (every one after that, via the P5-2 endpoint). A third,
 * {@code TenancyReconciler}, back-fills the alias for organisations that predate it.
 *
 * <p>They must agree. If provisioning derived a different slug from the reconciler, an organisation's
 * alias would silently change on the next boot — and the alias is part of every username created
 * under it, so the accounts already issued would no longer match the organisation they belong to.
 *
 * <h2>The constraint it has to satisfy</h2>
 *
 * {@code ck_organisation_alias} (V99) demands 2..20 characters, lowercase alphanumeric and hyphens,
 * with no leading or trailing hyphen. A name of {@code "A"} or {@code "!!!"} fails all of that, which
 * is why the fallback exists rather than being defensive padding.
 */
public final class OrganisationAlias {

    private OrganisationAlias() {
    }

    /**
     * A slug for {@code name}, or an {@code org-<id>} fallback when the name yields nothing usable.
     *
     * @param id used only for the fallback; may be null before the row is persisted, in which case a
     *           name that slugs to fewer than two characters returns null and the caller should
     *           derive the alias after the insert instead of guessing
     */
    public static String derive(String name, Long id) {
        String slug = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > 20) {
            slug = slug.substring(0, 20).replaceAll("-+$", "");
        }
        if (slug.length() < 2) {
            return id == null ? null : "org-" + id;
        }
        return slug;
    }
}
