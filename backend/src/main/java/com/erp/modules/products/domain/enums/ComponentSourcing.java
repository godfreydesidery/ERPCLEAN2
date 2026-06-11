package com.erp.modules.products.domain.enums;

/**
 * Make-vs-buy sourcing classification of a BOM component line (ADR-0026 D-3, FR-BOM-08, BR-BOM-07).
 *
 * <ul>
 *   <li>{@link #MAKE} — the component is manufactured in-house; it has (or is expected to have)
 *       its own ACTIVE BOM and is recursed into during multi-level explosion.</li>
 *   <li>{@link #BUY} — the component is purchased/raw; it is a leaf and is never exploded further,
 *       even if it happens to have a BOM (the line's classification wins).</li>
 * </ul>
 *
 * The default for a newly added component is derived at service time: child has an ACTIVE BOM
 * ⇒ MAKE; else BUY (BR-BOM-07); the caller may override explicitly.
 */
public enum ComponentSourcing {

    /** Manufactured in-house; recurse during explosion. */
    MAKE,

    /** Purchased / raw material; leaf node, never exploded. */
    BUY
}
