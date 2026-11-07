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
     * Aliases nobody may hold, because the alias becomes the suffix of every username in that tenant
     * ({@code user@alias}, D-7).
     *
     * <p>A customer slugging to {@code admin} would mint {@code rootadmin@admin} and
     * {@code joseph@admin}, which read as platform-level accounts to anyone glancing at an audit
     * trail or a support request. {@code system} and {@code root} carry the same implication;
     * {@code api} and {@code support} are the two most likely to be wanted later for a URL or a
     * mailbox, and taking one back from a live customer means rewriting every username they have.
     *
     * <p>Enforced HERE and not as a database {@code CHECK} deliberately. The format lives in
     * {@code ck_organisation_alias} because a shape is a shape; a blocklist is a product decision
     * that will change, and expressing it in both places would give one rule two sources of truth.
     * The service is also the half that can answer with a sentence rather than a constraint
     * violation (D-7a, 2026-08-16).
     */
    private static final java.util.Set<String> RESERVED =
            java.util.Set.of("admin", "root", "system", "api", "support");

    /**
     * A slug for {@code name}, or an {@code org-<id>} fallback when the name yields nothing usable.
     *
     * @param id used only for the fallback; may be null before the row is persisted, in which case a
     *           name that slugs to fewer than two characters returns null and the caller should
     *           derive the alias after the insert instead of guessing
     */
    /**
     * Whether a proposed organisation name is, or imitates, "platform" (owner, 2026-11-07).
     *
     * <p>Lookalike characters are folded first (0 to o, 1 | ! to l, 3 to e, 4 @ to a, 5 to s,
     * 7 to t, "rn" to m) and anything outside a-z is dropped. Refused: a WORD within two edits of
     * "platform" (insert, delete, substitute, swap) - "Platform", "Pllatform", "P1atform",
     * "Plattform", "Platform Ltd" - or a name that is exactly "platform" once spaces and punctuation
     * are removed - "Pla tform", "Plat-form". Allowed: "Plan & Form", "Platinum Foods",
     * "Plateau Traders".
     */
    public static boolean resemblesPlatform(String name) {
        if (name == null) {
            return false;
        }
        String folded = name.toLowerCase(Locale.ROOT)
                .replace("rn", "m")
                .replace('0', 'o').replace('1', 'l').replace('|', 'l').replace('!', 'l')
                .replace('3', 'e').replace('4', 'a').replace('@', 'a').replace('5', 's')
                .replace('7', 't');
        // A TYPO: any single word within two edits of "platform".
        for (String word : folded.split("[^a-z]+")) {
            if (word.length() >= 6 && word.length() <= 10 && editDistance(word, "platform") <= 2) {
                return true;
            }
        }
        // A SPACE or punctuation inside it: the separators removed give exactly "platform". Exact
        // on purpose (owner, 2026-11-07) - "Plan & Form" joins to "planform" and is allowed.
        return folded.replaceAll("[^a-z]+", "").equals("platform");
    }

    /** Optimal string alignment distance (Damerau-Levenshtein with adjacent swaps). */
    static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

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
        // A reserved slug falls back to org-<id> rather than being refused. Refusing would block a
        // customer legitimately called "Admin Supplies Ltd" from being created at all, over a name
        // they cannot change — and this method is called by BOTH provisioning and the reconciler,
        // so a throw here would take an existing tenant's boot down rather than just its alias.
        // org-<id> is unambiguous, already the fallback for an unusable name, and the operator can
        // still be given a better one by hand.
        if (RESERVED.contains(slug)) {
            return id == null ? null : "org-" + id;
        }
        return slug;
    }
}
